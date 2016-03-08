package monto.service;

import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import monto.service.configuration.Option;
import monto.service.message.ConfigurationMessage;
import monto.service.message.ConfigurationMessages;
import monto.service.message.DeregisterService;
import monto.service.message.Language;
import monto.service.message.LongKey;
import monto.service.message.Message;
import monto.service.message.ParseException;
import monto.service.message.Product;
import monto.service.message.ProductDependency;
import monto.service.message.ProductMessages;
import monto.service.message.RegisterMessages;
import monto.service.message.RegisterServiceRequest;
import monto.service.message.RegisterServiceResponse;
import monto.service.message.ServiceID;
import monto.service.message.Source;
import monto.service.message.VersionMessages;
import monto.service.util.PartialConsumer;
import monto.service.util.PartialFunction;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import de.tudarmstadt.stg.monto.connection.Pair;
import de.tudarmstadt.stg.monto.connection.Publish;
import de.tudarmstadt.stg.monto.connection.PublishSource;
import de.tudarmstadt.stg.monto.connection.Sink;
import de.tudarmstadt.stg.monto.connection.Subscribe;
import de.tudarmstadt.stg.monto.java8.JavaCodeCompletion;
import de.tudarmstadt.stg.monto.java8.JavaOutliner;
import de.tudarmstadt.stg.monto.java8.JavaParser;
import de.tudarmstadt.stg.monto.java8.JavaTokenizer;
import de.tudarmstadt.stg.monto.message.MessageListener;
import de.tudarmstadt.stg.monto.message.ProductMessage;
import de.tudarmstadt.stg.monto.message.ProductRegistry;
import de.tudarmstadt.stg.monto.message.ProductRegistry.ProductItem;
import de.tudarmstadt.stg.monto.message.Source;
import de.tudarmstadt.stg.monto.message.VersionMessage;
import de.tudarmstadt.stg.monto.profiling.Profiler;
import de.tudarmstadt.stg.monto.server.Server;

/**
 * Template for a monto service.
 */
@SuppressWarnings("rawtypes")
public abstract class MontoService {

    private ZMQConfiguration zmqConfig;
    private int port;
    private volatile boolean running;
    private boolean registered;

    protected volatile ServiceID serviceID;
    protected volatile String label;
    protected volatile String description;
    protected volatile Language language;
    protected volatile Product product;
	protected volatile Option[] options;
    protected volatile String[] dependencies;
	private Socket registrationSocket;
	private Socket serviceSocket;
	private Thread serviceThread;
	private Thread configThread;
	private Socket configSocket;

    /**
     * Template for a monto service.
     *
     * @param context
     * @param fullServiceAddress             address of the service without port, e.g. "tcp://*"
     * @param registrationAddress registration address of the broker, e.g. "tcp://*:5004"
     * @param serviceID
     * @param product
     * @param language
     * @param dependencies
     */
    public MontoService(
    		ZMQConfiguration zmqConfig,
    		ServiceID serviceID,
    		String label,
    		String description,
    		Product product,
    		Language language,
    		String[] dependencies
    		) {
    	this.zmqConfig = zmqConfig;
        this.serviceID = serviceID;
        this.label = label;
        this.description = description;
        this.language = language;
        this.product = product;
        this.options = new Option[]{};
        this.dependencies = dependencies;
        this.running = true;
        this.registered = false;
    }

    /**
     * Template for a monto service with options.
     *
     * @param context
     * @param fullServiceAddress             address of the service without port, e.g. "tcp://*"
     * @param registrationAddress registration address of the broker, e.g. "tcp://*:5004"
     * @param serviceID
     * @param label
     * @param description
     * @param language
     * @param product
     * @param options
     * @param dependencies
     */
    public MontoService(ZMQConfiguration zmqConfig, ServiceID serviceID, String label, String description, Language language, Product product, Option[] options, String[] dependencies) {
        this(zmqConfig, serviceID, label, description, product, language, dependencies);
        this.options = options;
    }

    public <MSG,Decoded> void handleMessage(Socket socket, PartialFunction<MSG,Decoded,ParseException> decodeMessage, PartialConsumer<Decoded, ? super Exception> onMessage) {
    	String rawMsg = socket.recvStr();
		try {
			// In case of subscription ignore topic and receive message body
			if(socket.getType() == ZMQ.SUB && rawMsg != null)
				rawMsg = socket.recvStr();

			if (rawMsg != null) {
				@SuppressWarnings("unchecked")
				MSG msg = (MSG) JSONValue.parseWithException(rawMsg);
				Decoded decoded = decodeMessage.apply(msg);
				onMessage.accept(decoded);
			}
		} catch (Exception e) {
			System.err.printf("An exception occured during handling the message %s\n",rawMsg);
			e.printStackTrace();
		}
    }
    
    public void start() {
        registerService();
        if (isRegisterResponseOk()) {
        	running = true;
        	serviceSocket = zmqConfig.getContext().createSocket(ZMQ.PAIR);
        	serviceSocket.connect(zmqConfig.getServiceAddress() + ":" + port);
        	serviceSocket.setReceiveTimeOut(500);
        	MontoService that = this;
        	serviceThread = new Thread() {
        		@Override
        		public void run() {
        			while(running)
        				that.<JSONArray,List<Message>>handleMessage (
        						serviceSocket,
        						messages -> {
        							List<Message> decodedMessages = new ArrayList<>();
        							for (Object object : messages) {
        								JSONObject message = (JSONObject) object;
        								decodedMessages.add(message.containsKey("product") ? ProductMessages.decode(message) : VersionMessages.decode(message));
        							}
        							return decodedMessages;
        						},
        						messages -> serviceSocket.send(ProductMessages.encode(onVersionMessage(messages)).toJSONString()));
        		}
        	};
        	serviceThread.start();
        	
        	configSocket = zmqConfig.getContext().createSocket(ZMQ.SUB);
        	configSocket.connect(zmqConfig.getConfigurationAddress());
        	configSocket.subscribe(serviceID.toString().getBytes());
        	configSocket.setReceiveTimeOut(500);
        	configThread = new Thread() {
        		@Override
        		public void run() {
        			while(running) {
        				that.<JSONObject,ConfigurationMessage>handleMessage(
        						configSocket,
        						ConfigurationMessages::decode,
        						message -> onConfigurationMessage(message));
        			}s
        		};
        	};
        	configThread.start();
        	System.out.println("connected: " + serviceID);
        }
    }

    public void stop() {
        if (registered == true) {
            running = false;
            System.out.println("disconnecting: " + serviceID);
            System.out.println("deregistering: " + serviceID);
            try {
				serviceThread.join();
				configThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
            registrationSocket.send(RegisterMessages.encode(new DeregisterService(serviceID)).toJSONString());
            
            // Sockets will be closed by ZContext.destroy()
            
            System.out.println("terminated: " + serviceID);
        }
    }

    private void registerService() {
        System.out.println("registering: " + serviceID + " on " + zmqConfig.getRegistrationAddress());
        registrationSocket = zmqConfig.getContext().createSocket(ZMQ.REQ);
        registrationSocket.connect(zmqConfig.getRegistrationAddress());
        registrationSocket.send(RegisterMessages.encode(new RegisterServiceRequest(serviceID, label, description, language, product, options, dependencies)).toJSONString());
    }

    private boolean isRegisterResponseOk() {
        JSONObject response = (JSONObject) JSONValue.parse(registrationSocket.recvStr());
        RegisterServiceResponse decodedResponse = RegisterMessages.decodeResponse(response);
        if (decodedResponse.getResponse().equals("ok") && decodedResponse.getBindOnPort() > -1) {
            port = decodedResponse.getBindOnPort();
            System.out.println("registered: " + serviceID + ", connecting on " + zmqConfig.getServiceAddress() + ":" + port);
            registered = true;
            return true;
        }
        System.out.printf("could not register service %s: %s\n", serviceID, decodedResponse.getResponse());
        return false;
    }
    
    protected ProductMessage productMessage(LongKey versionID, Source source, Object contents, ProductDependency ... deps) {
        return new ProductMessage(
                versionID,
                source,
                getServiceID(),
                getProduct(),
                getLanguage(),
                contents,
                deps);
    }

    /**
     * It handles the version messages from the broker and determines the response.
     *
     * @param messages VersionMessage an dependency ProductMessages
     * @return a ProductMessage for the service
     * @throws Exception
     */
    public abstract ProductMessage onVersionMessage(List<Message> messages) throws Exception;

    /**
     * It handles the configuration messages from the broker and determines the response.
     *
     * @param message The received configuration message
     * @throws Exception
     */
    public void onConfigurationMessage(ConfigurationMessage message) throws Exception {
    	// By default ignore configuration messages.
    }

    public ServiceID getServiceID() {
        return serviceID;
    }

    public Language getLanguage() {
        return language;
    }

    public Product getProduct() {
        return product;
    }

    public String[] getDependencies() {
        return dependencies;
    }

    public Option[] getOptions() {
        return options;
    }

 private class Activator extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				//new JsonPrettyPrinter(new Connection(ctx, "tcp://localhost:5004"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator2 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				//new JsonPrettyPrinter(new Connection(ctx, "tcp://localhost:5004"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator3 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				//new JsonPrettyPrinter(new Connection(ctx, "tcp://localhost:5004"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator4 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				//new JsonPrettyPrinter(new Connection(ctx, "tcp://localhost:5004"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator5 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator6 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator7 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator8 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator9 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator10 extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
	public static Activator getDefault() {
		return plugin;
	}
	public static Profiler getProfiler() {
		return getDefault().profiler;
	}
	public static void debug(String msg, Object ... formatArgs) {
		getDefault().getLog().log(new Status(Status.INFO, PLUGIN_ID, String.format(msg,formatArgs)));
	}
	public static void error(Exception e) {
		error(null, e);
	}
	public static void error(String msg, Exception e) {
		getDefault().getLog().log(new Status(Status.ERROR, PLUGIN_ID, msg, e));
	}
	public static void addMessageListener(MessageListener listener) {
		getDefault().messageListeners.add(listener);
	}
	public static void removeMessageListener(MessageListener listener) {
		getDefault().messageListeners.remove(listener);
	}
	public static void sendMessage(VersionMessage version) {
		getDefault().source.sendMessage(version);
	}

}
private class Activator extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "monto"; //$NON-NLS-1$
	private static Activator plugin;
	private Profiler profiler;
	private List<Server> servers;
	private Set<MessageListener> messageListeners = new HashSet<>();
	private ProductRegistry products = new ProductRegistry();
	private PublishSource source;
	private Sink sink;
	public void start(BundleContext bundle) throws Exception {
		super.start(bundle);
		plugin = this;
		String profFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-kk-mm-ss"))+".csv";
		profiler = new Profiler(new PrintWriter(new BufferedWriter(new FileWriter(profFile))));
		Context ctx = ZMQ.context(1);
		source = new PublishSource(new Publish(ctx, "tcp://localhost:5000"));
		source.connect();
		sink = new Sink(new Subscribe(ctx, "tcp://localhost:5001")) {
			@Override public void onMessage(ProductMessage msg) {
				for(MessageListener listener : messageListeners)
					listener.onMessage(msg);
				products.registerProduct(msg.getSource(),msg.getProduct(),msg.getLanguage());
			}
		};
		sink.fork();
		servers = Arrays.asList(
				new JavaTokenizer(new Pair(ctx, "tcp://localhost:5010")),
				new JavaParser(new Pair(ctx, "tcp://localhost:5011")),
				new JavaOutliner(new Pair(ctx, "tcp://localhost:5012")),
				new JavaCodeCompletion(new Pair(ctx, "tcp://localhost:5013"))
				);
		servers.forEach(server -> server.fork());
	}
}
}
