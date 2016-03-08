public class Activator extends AbstractUIPlugin {
	public void stop(BundleContext bundle) throws Exception {
		source.close();
		sink.stop();
		servers.forEach(server -> server.stop());
		profiler.close();
		plugin = null;
		super.stop(bundle);
	}
}
