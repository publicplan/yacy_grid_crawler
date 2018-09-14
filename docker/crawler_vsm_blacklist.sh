#!/usr/bin/env sh

for format in year vJ cjahr yearID month date \\/; do
  currentYear=$(date +'%Y')
  for sub in 4 3 -3 -4; do
    year=`expr $currentYear - $sub`
    if [ $format == "\\/" ]; then
      echo ".*\\/$year\\/.*" >> "$1"
    else
      echo ".*$format=$year.*" >> "$1"
    fi
  done
done


