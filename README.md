# SimpleRawCamera

Simple photo camera inspired from real camera look.
It use Camera2 API and supports only cameras with full AE / focus control.

![Main View](img/help.jpg)

Tested only on my LG G6 smartphone.

## Exposure

It support only ISO & Speed (as most smart phone cameras has fixed aperture).

Supported modes:
* Full auto
* Full manual (1 EV step only)
* Semi-automatic (1 EV step only):
  * Manual ISO with automatic Speed
  * Manual Speed with automatic ISO (WARNING: the ISO range is limited and it may not be possible to have one for the specified speed)

## Focus

Supported modes:
* Automatic (continuous)
* Hyperfocal (fixed)
* Manual:
  * Select using the slider
  * Click to focus
 
## Flash
 
Not supported yet.
 
Desired features:
* Automatic
* Manual
* Continuous flash (flashlight)
 
## White balance
 
Not supported yet.
 
## Output modes:

* JPEG
* RAW/DNG
* JPEG + RAW/DNG

## Photo mode:

* Single shot
* Continuous
 
## Helpers:
* Show roules of third grid
* Show frame for another radio than the sensor
 
## TODO:
 
* Exposure:
  * Try to adjust exposure (except for full manual mode) to avoid burning highlights
 
* Optimizations:
  * Continuous photo mode: try reduce save time to take the next photo faster

* Settings:
  * Allow to change output folder
  * Add noise reductions option
  * Add "location" option
 
* Features:
  * Add timer (2 / 10 seconds)
  * Add continuous photo with a timer (every: 1 second, 5 seconds, 10 seconds ... 1 min) 
