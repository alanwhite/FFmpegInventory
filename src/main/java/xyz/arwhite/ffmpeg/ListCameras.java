/*
	Copyright (c) Alan R.White. All rights reserved.  
	Licensed under the MIT License. See LICENSE file in the project root for full license information.  
*/

package xyz.arwhite.ffmpeg;

/***
 * Example how to use the FFmpegInventory class to obtain the list of cameras and a subset of capabilities
 * 
 * @author Alan White
 *
 */
public class ListCameras {

	public static void main(String[] args) {
		FFmpegInventory inventory = new FFmpegInventory();

		inventory.getCameraInventory().forEach((device, cam) -> {
			System.out.println("Device=" + device + ", Format=" + cam.format + ", Name=" + cam.name);
			cam.modes.forEach(m -> {
				System.out.print("  Resolution=" + m.width + "x" + m.height);
				
				/*
				 * If there's an absolute list of supported fps and resolurion the validFPS list is used.
				 * If there's a min/max range of FPS that can be requested in a device, the minFPS and maxFPS are used.
				 */
				if (m.validFPS != null) {
					System.out.print(", valid fps rates are ");
					m.validFPS.forEach(fps -> System.out.print(fps + " "));
					System.out.println("");
				} else {
					System.out.println(", minFPS="+m.minFPS+", maxFPS="+m.maxFPS);
				}
			});
		});
		
		// If a new device has been plugged in then FFMpegInventory.refreshCameras(inventory) can be called
		 
	}

}
