# FFmpegInventory

There is no mechanism within ffmpeg libraries for javacv to return a list of devices and their capabilities.
This project provides a way to build that inventory of devices by triggering ffmpeg to report the devices and their capabilities to stderr, intercepting and then parsing that output and storing in a java class.

ListCameras.java is an example of how to use the FFmpegInventory class.

Only understands the avfoundation interface on macos for now, windows dshow on its way.

Contributions for other interfaces gratefully received
 

 
 


 
