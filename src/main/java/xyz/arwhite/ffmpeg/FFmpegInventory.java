package xyz.arwhite.ffmpeg;

import static org.bytedeco.ffmpeg.global.avutil.setLogCallback;

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

public class FFmpegInventory {

	public FFmpegInventory() {
		cameraInventory = new HashMap<>();
		FFmpegInventory.refreshCameras(this);
	}
	
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


	public static void refreshCameras(FFmpegInventory cameras) {
		var cams = cameras.getCameraInventory();
		cams.clear();

		var origErr = System.err;
		avdevice.avdevice_register_all();
		InventoryCallback.set();

		// first get the list of known AV interfaces, aka formats
		List<AVInputFormat> formats = new ArrayList<>();
		AVInputFormat inp = null;
		while ((inp = avdevice.av_input_video_device_next(inp)) != null) {
			formats.add(inp);
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

			switch (format.name().getString()) {
			case "avfoundation":
				parseAVFoundation(format, tmp, cameras);
				getAVFoundationDeviceCaps(cameras);
				break;
			case "dshow":
				System.out.println("Windows implementation tbd still");
				break;
			}
		}
	}

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

				// System.out.println(p[1]);

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

				// System.out.println("Supports "+width+"x"+height+"@"+fps);

				var cam = cameras.getCameraInventory().get(device);

				// check cam modes for this resolution
				var matchedModeOpt = cam.modes.stream().filter(m -> m.height == height && m.width == width).findFirst();

				CameraMode ff = null;
				if (matchedModeOpt.isPresent())
					ff = matchedModeOpt.get();
				else {
					ff = cameras.new CameraMode();
					cam.modes.add(ff);
					ff.height = height;
					ff.width = width;
					ff.validFPS = new ArrayList<Double>();
				}

				ff.validFPS.add(fps);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}


}
