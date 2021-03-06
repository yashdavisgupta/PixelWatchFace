# PixelWatchFace
A minimalistic and open-source watchface for WearOS

Play Store link: https://play.google.com/store/apps/details?id=com.corvettecole.pixelwatchface

# Roadmap
## In-Progress
### Second Update
- Full rehaul of mobile app so settings are easy to navigate
- Dynamic stroke width based on ambient lighting
- Add setting to allow user to change update interval for weather
### Third Update
- Add option to allow user to support me by giving money
- Add all watch settings to watch app, so phone is no longer needed
- Replace weather icons with new ones that match the ones on Pixel phones
- Add support for Dark Sky weather summary
### Fourth Update
- Add the "At A Glance" widget
- Something else, idk

## Completed
### First Update (Refactor time woooo!)
- Create standard interface for settings on both devices, allowing new features to be implemented easily
- Move all constants to constants
- Move all strings to a strings xml to allow for translation
- Make the permissions dialog keep showing if the user doesn't accept and weather is still enabled
- Add battery percent
- Add setting to allow user to toggle outlined text in ambient mode
- Make code more modular and independent
  

## Misc (can ignore)
- Add subtle explanations that explain what extra features using the Dark Sky API gives you
- Add yearly subscription that covers the cost of Dark Sky API calls for a year 
If the watch checked the Dark Sky weather API every 30 minutes for 365 days it would cost $1.752 USD. Google takes a 30% cut so 1.752 = x - .3x.  x = $2.503 USD as the minimum yearly subscription cost. If I make that a simple $3/year Google would take $0.9 leaving $2.1 left over. The API costs would take $1.752 from that meaning I would make $0.348 per year per person. That is alright I guess.
- Add donation options for supporting me and my projects
- Add crowdin localization to the phone app, play store listing, and watch app where possible
- Add weather.gov as a source for US users (https://api.weather.gov/points/38.0301,-84.4988/forecast for example)
