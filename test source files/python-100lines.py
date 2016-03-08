#! python
# -*- coding: utf-8 -*- taken from https://github.com/FreeCAD/FreeCAD/blob/master/BuildRelease.py
# (c) 2007 Jürgen Riegel  GPL 

Usage = """BuildRelease - Build script to build a complete FreeCAD release
Usage:
   BuildRelease [Optionen] ReleaseNbr
   
Options:
 -h, --help          print this help
 -b, --buildPath     specify the output path where the build takes place
 -i, --ini-file      specify the ini file to use
 
This script will build a complete FreeCAD distribution which includes:
* Check out fresh source
* packing source
* Set the Version and Release numbers
* Gathering change log
* completele build FreeCAD
* run tests
* build source docu
* build user docu
* build installer
* upload to source forge 
   
On failure of one of these steps the script will stop.
Each step writes tones of info in the log file.
There is one error log file.
Autor:
  (c) 2007 Juergen Riegel
  juergen.riegel@web.de
	Licence: GPL
Version:
  0.1
"""
#  
# Its inteded only to used by the maintainer

import os, sys, getopt
from subprocess import call,Popen,PIPE
from time import sleep
from zipfile import ZipFile,ZIP_DEFLATED
import tarfile
from string import find
import ConfigParser
import time


# global information 
Release = 0
Major = 0
Minor = 7
Alias = ""
FileName = ""
BuildPath = "D:/ReleaseBuilds"
Log = None
ErrLog = None
Config = None


def CallProcess(args,Msg,ret=True):
	Anim = ['-','\\','|','/']
	
	sys.stdout.write(Msg+':  ')
	Log.write("====== Call: " + args[0] + '\n')
	SVN = Popen(args,
	            stdout=PIPE, stderr = ErrLog)
	
	i = 0
	while(SVN.poll() == None):
		line = SVN.stdout.readline()
		if(line):
			Log.write(line.replace('\n',''))
		sys.stdout.write(chr(8) + Anim[i%4])
		i+=1
		sleep(0.2)
	
	#ErrLog.write(SVN.stdout.read())
	sys.stdout.write(chr(8) + "done\n")
	if(not SVN.returncode == 0 and ret):
		print "Process returns: ",SVN.returncode
		raise

# Step 2 & 3
def CheckOut():
	
	CallProcess([Config.get('Tools','svn'), 
	             "checkout",
				 "-r",
				 `Release`,
				 "https://free-cad.svn.sourceforge.net/svnroot/free-cad/trunk",
				 "../"+FileName],
				 "2) Checking out")

	sys.stdout.write('3) Write version files: ')
	
	Version = open("src/Build/Version.h","w")
	Version.write('#define FCVersionMajor "' + `Major` + '"\n')
	Version.write('#define FCVersionMinor "' + `Minor` + '"\n')
	Version.write('#define FCVersionName "' + Alias + '"\n')
