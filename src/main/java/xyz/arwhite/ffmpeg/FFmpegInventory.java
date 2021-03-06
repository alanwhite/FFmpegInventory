
/*
	Copyright (c) Alan R.White. All rights reserved.  
	Licensed under the MIT License. See LICENSE file in the project root for full license information.  
*/

package xyz.arwhite.ffmpeg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.ffmpeg.global.avdevice;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import static org.bytedeco.ffmpeg.global.avutil.setLogCallback;

public class FFmpegInventory {

	public FFmpegInventory() {
		cameraInventory = new HashMap<>();
		FFmpegInventory.refreshCameras(this);
	}
	
	/*
	 * Used to intercept all log messages from ffmpeg native libraries
	 */
	static class InventoryCallback extends LogCallback {
		static final InventoryCallback instance = new InventoryCallback().retainReference();

		public static InventoryCallback getInstance() {
			return instance;
		}

		public static void set() {
			setLogCallback(getInstance());
		}

		@Override
		public void call(int level, BytePointer msg) {
			System.err.print(msg.getString());
		}
	}

	class CameraMode {
		int width, height;
		double minFPS; // not used if device only supports fixed rate, see validFPS below
		double maxFPS; // not if device only supports fixed rate, see validFPS below
		List<Double> validFPS; // only used if the device only supports fixed rates, null otherwise
	}

	class VideoCamera {
		String format; // AVInputFormat.name, the -f avfoundation on macOS, -f dshow on windows, linux tbd
		String name; // the name of the device as returned by ffmpeg -f avfoundation -list_devices true
		List<CameraMode> modes = new ArrayList<CameraMode>();
	}

	private Map<Integer, VideoCamera> cameraInventory;
	public Map<Integer, VideoCamera> getCameraInventory() {
		return cameraInventory;
	}

	/***
	 * Re-registers all devices with ffmpeg and populates the provided cameras object
	 * @param cameras
	 */
	public static void refreshCameras(FFmpegInventory cameras) {
		var cams = cameras.getCameraInventory();
		cams.clear();

		var origErr = System.err;
		avdevice.avdevice_register_all(); //<== this can take a noticeable period of time on some systems
		InventoryCallback.set();

		// first get the list of known AV interfaces, aka formats, from ffmpeg -devices
		List<AVInputFormat> formats = new ArrayList<>();
		AVInputFormat inp = null;
		while ((inp = avdevice.av_input_video_device_next(inp)) != null) {
			switch (inp.name().getString()) {
			case "avfoundation":
			case "dshow":
				formats.add(inp);
			}	
		}

		for (AVInputFormat format : formats) {
			// get the contents of ffmpeg -f <format> -list_devices true -i "" in a file
			File tmp = null;
			try {
				tmp = File.createTempFile(Long.toString(System.currentTimeMillis()), null);
				System.setErr(new PrintStream(tmp));

				FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("");
				grabber.setFormat(format.name().getString());
				grabber.setOption("list_devices", "true");
				grabber.start();
			} catch (Exception e) { e.printStackTrace(); }

			System.setErr(origErr);
			// System.out.println(format.name().getString());
			
			switch (format.name().getString()) {
			case "avfoundation":
				parseAVFoundation(format, tmp, cameras);
				getAVFoundationDeviceCaps(cameras);
				break;
			case "dshow":
				parseDShow(format, tmp, cameras);
				getDShowDeviceCaps(cameras);
				break;
			}
		}
	}
	
	/***
	 * Parses the stderr output from the equivalent of ffmpeg -f avfoundation -list_devices true
	 * @param format
	 * @param file
	 * @param cameras
	 */
	private static void parseAVFoundation(AVInputFormat format, File file, FFmpegInventory cameras) {

		// extract the device numbers and names from the device lister output
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			boolean foundVideoHeader = false;
			String line;
			while ((line = br.readLine()) != null) {
				// split off the prefix
				String[] s = line.split("] ", 2);
				if (s.length != 2)
					continue;

				// split into a word and rest of line
				String[] p = s[1].split(" ", 2);
				if (p.length != 2)
					continue;

				if (!foundVideoHeader) {
					if (p[0].equals("AVFoundation"))
						foundVideoHeader = true;
					continue;
				}

				// see if we're past the list of devices
				if (!p[0].startsWith("["))
					break;

				// exclude screen capture pseudo devices
				if (p[1].startsWith("Capture screen"))
					continue;

				// processing a video device
				String devString = p[0].substring(1, p[0].length() - 1);
				var f = cameras.new VideoCamera();
				cameras.getCameraInventory().put(Integer.parseInt(devString), f);
				f.format = "avfoundation";
				f.name = p[1];
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	/***
	 * Parses the stderr output from the equivalent of ffmpeg -f dshow -list_devices true
	 * @param format
	 * @param file
	 * @param cameras
	 */
	private static void parseDShow(AVInputFormat format, File file, FFmpegInventory cameras) {
		
		int deviceNumber=0;
		
		// extract the device numbers and names from the device lister output
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			boolean foundVideoHeader = false;
			String line;
			while ((line = br.readLine()) != null) {
				// System.out.println(line);
				// split off the prefix
				String[] s = line.split("] ", 2);
				if (s.length != 2)
					continue;

				// System.out.println(s[1]);
				
				// split into a word and rest of line
				String[] p = s[1].split(" ", 2);
				if (p.length != 2)
					continue;
				
				// System.out.println(p[0]);
				// System.out.println(p[1]);

				if (!foundVideoHeader) {
					if (p[0].equals("DirectShow"))
						foundVideoHeader = true;
					continue;
				}

				// see if we're past the list of devices <===== CHANGE to be a != on something
				if (p[0].startsWith("DirectShow"))
					break;


				
			
				// see if it's the Alternative name and skip
				var x = p[1].trim();
				// System.out.println(x);
				if ( x.startsWith("Alternative") )
					continue;
				
				// processing a video device
				var f = cameras.new VideoCamera();
				cameras.getCameraInventory().put(deviceNumber++, f);
				f.format = "dshow";
				f.name = p[1].replace("\"","");
				
				// System.out.println(f.name);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	/***
	 * Gets output from ffmpeg -f dshow -list_options -i video="camera name"
	 * @param cameras
	 */
	private static void getDShowDeviceCaps(FFmpegInventory cameras) {
		var origErr = System.err;
		File tmp = null;
		var keys = cameras.getCameraInventory().keySet();
		for (Integer device : keys) {
			try {
				tmp = File.createTempFile(Long.toString(System.currentTimeMillis()), null);
				System.setErr(new PrintStream(tmp));

				FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(
						"video=" +
						cameras.getCameraInventory().get(device).name);
				grabber.setFormat(cameras.getCameraInventory().get(device).format);
				grabber.setOption("list_options", "true");
				grabber.start();
			} catch (Exception e) { e.printStackTrace(); }

			System.setErr(origErr);
			parseDShowDeviceCaps(tmp, cameras, device);
		}
	}

	/***
	 * Makes a fake attempt to record video, providing a deliberately bad frame rate to cause
	 * avfoundation to error out and list the resolutions and frame rates it knows about
	 * @param cameras
	 */
	private static void getAVFoundationDeviceCaps(FFmpegInventory cameras) {
		var origErr = System.err;
		File tmp = null;
		var keys = cameras.getCameraInventory().keySet();
		for (Integer device : keys) {
			try {
				tmp = File.createTempFile(Long.toString(System.currentTimeMillis()), null);
				System.setErr(new PrintStream(tmp));

				FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(device.toString());
				grabber.setFormat(cameras.getCameraInventory().get(device).format);
				grabber.setFrameRate(713); // ridiculous value to trigger list of values
				grabber.start();
			} catch (Exception e) { e.printStackTrace(); }

			System.setErr(origErr);
			parseAVFoundationDeviceCaps(tmp, cameras, device);
		}

	}
	
	private static void parseDShowDeviceCaps(File file, FFmpegInventory cameras, Integer device) {
		// extract the device numbers and names from the device lister output
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			boolean foundPin = false;
			String line;
			while ((line = br.readLine()) != null) {
				// break into 2 pieces, the first half eliminates the [dshow @ .. ]
				String[] s = line.split("] ", 2);
				if (s.length != 2)
					continue;

				// break out the 2nd token into space delimited strings
				String[] p = s[1].split(" ", 2);
				if (p.length != 2)
					continue;
				
				if (!foundPin) {
					if (p[1].startsWith("Pin"))
						foundPin = true;
					continue;
				}
				
				// if we are past the list of supported modes then we're done
				if (p[0].equals("Could")) 
					break;
				
				String[] words = p[1].split(" ");
				
				var res=words[4];
				var minfps=words[5];
				var maxfps=words[8];
				
				var resOff = res.substring(2);
				var width = Integer.parseInt(resOff.substring(0,resOff.indexOf("x")));
				var height = Integer.parseInt(resOff.substring(resOff.indexOf("x")+1));
				
				var minFPS = Double.parseDouble(minfps.substring(minfps.indexOf("=")+1));
				var maxFPS = Double.parseDouble(maxfps.substring(maxfps.indexOf("=")+1));
				
				var cam = cameras.getCameraInventory().get(device);

				// check cam modes for this resolution
				var matchedModeOpt = cam.modes
						.stream()
						.filter(m -> m.height == height && m.width == width)
						.findFirst();

				CameraMode cameraMode = null;
				if (matchedModeOpt.isPresent())
					cameraMode = matchedModeOpt.get();
				else {
					cameraMode = cameras.new CameraMode();
					cam.modes.add(cameraMode);
					cameraMode.height = height;
					cameraMode.width = width;
					cameraMode.minFPS = 1024.0f;
					cameraMode.maxFPS = 0.0f;
				}
				
				if ( maxFPS > cameraMode.maxFPS ) 
					cameraMode.maxFPS = maxFPS;
				
				if ( minFPS < cameraMode.minFPS ) 
					cameraMode.minFPS = minFPS;

				

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/***
	 * Parses the stderr from the call to the failed ffmpeg recording attempt to extract the capabilities
	 * of the device and populates them in the inventory
	 * @param file
	 * @param cameras
	 * @param device
	 */
	private static void parseAVFoundationDeviceCaps(File file, FFmpegInventory cameras, Integer device) {
		// extract the device numbers and names from the device lister output
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			boolean foundSupportedModes = false;
			String line;
			while ((line = br.readLine()) != null) {
				// break into 2 pieces, the first half eliminates the [AVfo.. ]
				String[] s = line.split("] ", 2);
				if (s.length != 2)
					continue;

				// break out the 2nd token into space delimited strings
				String[] p = s[1].split(" ", 2);
				if (p.length != 2)
					continue;

				if (!foundSupportedModes) {
					if (p[0].equals("Supported"))
						foundSupportedModes = true;
					continue;
				}

				// if we are past the list of supported modes then we're done
				if (!p[1].endsWith("fps"))
					break;

				var mode = p[1].trim();

				var xOff = mode.indexOf("x");
				var width = Integer.parseInt(mode.substring(0, xOff));

				var atOff = mode.indexOf("@");
				var height = Integer.parseInt(mode.substring(xOff + 1, atOff));

				var spaceOff = mode.indexOf(" ");
				var fps = Double.parseDouble(mode.substring(atOff + 2, spaceOff));

				var cam = cameras.getCameraInventory().get(device);

				// check cam modes for this resolution
				var matchedModeOpt = cam.modes
						.stream()
						.filter(m -> m.height == height && m.width == width)
						.findFirst();

				CameraMode cameraMode = null;
				if (matchedModeOpt.isPresent())
					cameraMode = matchedModeOpt.get();
				else {
					cameraMode = cameras.new CameraMode();
					cam.modes.add(cameraMode);
					cameraMode.height = height;
					cameraMode.width = width;
					cameraMode.validFPS = new ArrayList<Double>();
				}

				cameraMode.validFPS.add(fps);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}


}
