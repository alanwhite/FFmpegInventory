/*
	Copyright (c) Alan R.White. All rights reserved.  
	Licensed under the MIT License. See LICENSE file in the project root for full license information.  
*/

package xyz.arwhite.ffmpeg;

public class ListCameras {

	public static void main(String[] args) {
		FFmpegInventory inventory = new FFmpegInventory();

		inventory.getCameraInventory().forEach((device, cam) -> {
			System.out.println("Device=" + device + ", Format=" + cam.format + ", Name=" + cam.name);
			cam.modes.forEach(m -> {
				System.out.print("  Resolution=" + m.width + "x" + m.height);
				if (m.validFPS != null) {
					System.out.print(", valid fps rates are ");
					m.validFPS.forEach(fps -> System.out.print(fps + " "));
					System.out.println("");
				} else {
					System.out.println(", no valid fps rate recorded");
				}
			});
		});
	}

}
