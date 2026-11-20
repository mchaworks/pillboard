#!/bin/sh
# Downloads Google Sans Rounded (SIL OFL 1.1) into res/font. Run from the project root.
set -e
B=https://raw.githubusercontent.com/tiwa244/Google-Sans-Rounded/main
D=app/src/main/res/font
mkdir -p "$D" licenses
for w in Light Regular Medium Bold; do
  l=$(echo "$w" | tr 'A-Z' 'a-z')
  curl -fL --retry 2 -o "$D/gsr_$l.ttf" "$B/GoogleSansRounded-$w.ttf"
done
curl -fL -o licenses/GoogleSansRounded-OFL.txt "$B/LICENSE"
ls -l "$D"
