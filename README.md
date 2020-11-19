# FFmpegInventory

There is no mechanism within ffmpeg libraries for javacv to return a list of devices and their capabilities.
This project provides a way to build that inventory of devices by triggering ffmpeg to report the devices and their capabilities to stderr, intercepting and then parsing that output and storing in a java class. 

 
 


 
