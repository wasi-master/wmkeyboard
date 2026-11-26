#!/bin/sh
# Builds wmpy preview's page into dist/ (app.js, app.css), which is
# committed and compiled into the wmpy binary, so building wmpy needs no
# Node. Run this after changing entry.tsx, preview.css or the web editor's
# renderer it imports (docs/src/store/ui/creators/ide/V5Widgets.tsx, ide.css).
#
# Needs the docs site's node_modules (cd docs && npm install), whose esbuild,
# Preact and qrcode it uses.
set -e
here=$(cd "$(dirname "$0")" && pwd)
docs=$(cd "$here/../../../docs" && pwd)
mkdir -p "$here/dist"
export NODE_PATH="$docs/node_modules"
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=2048}"
"$docs/node_modules/.bin/esbuild" "$here/entry.tsx" \
	--bundle --minify --format=iife --target=es2020 \
	--jsx=automatic --jsx-import-source=preact \
	--legal-comments=none --charset=utf8 \
	--outfile="$here/dist/app.js"
ls -l "$here/dist"
