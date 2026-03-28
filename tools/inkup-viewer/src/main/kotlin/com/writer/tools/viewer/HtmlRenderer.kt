package com.writer.tools.viewer

import com.writer.model.proto.DocumentProto
import kotlin.math.max

object HtmlRenderer {

    fun render(doc: DocumentProto, filename: String): String {
        val isNormalized = (doc.coordinate_system ?: 0) == 1

        val mainBounds = ProtoToSvg.columnBounds(doc.main, isNormalized)
        val cueBounds = ProtoToSvg.columnBounds(doc.cue, isNormalized)

        // Use a reasonable default width if column is empty
        val mainWidth = max(mainBounds.maxX + 40f, 700f)
        val cueWidth = max(cueBounds.maxX + 40f, 300f)
        // Both columns share the same height for linked scroll
        val height = max(max(mainBounds.maxY, cueBounds.maxY) + 100f, 500f)

        val mainSvg = ProtoToSvg.columnToSvg(doc.main, isNormalized, mainWidth, height)
        val cueSvg = ProtoToSvg.columnToSvg(doc.cue, isNormalized, cueWidth, height)

        val escapedName = filename.replace("&", "&amp;").replace("<", "&lt;")

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>$escapedName - InkUp Viewer</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { display: flex; flex-direction: column; height: 100vh; background: #f5f5f0; font-family: system-ui, sans-serif; }
header { padding: 8px 16px; background: #333; color: #eee; font-size: 14px; flex-shrink: 0; display: flex; align-items: center; gap: 16px; }
header .title { font-weight: 600; }
header .meta { color: #aaa; font-size: 12px; }
.columns { display: flex; flex: 1; min-height: 0; }
.column { overflow: auto; background: white; }
#main-col { flex: 7; }
#cue-col { flex: 3; }
.divider { width: 4px; background: #555; flex-shrink: 0; }
svg { display: block; }
.empty-label { color: #999; padding: 40px; text-align: center; font-style: italic; }
</style>
</head>
<body>
<header>
  <span class="title">$escapedName</span>
  <span class="meta">Main: ${doc.main?.strokes?.size ?: 0} strokes | Cue: ${doc.cue?.strokes?.size ?: 0} strokes</span>
</header>
<div class="columns">
  <div id="main-col" class="column">
    $mainSvg
  </div>
  <div class="divider"></div>
  <div id="cue-col" class="column">
    ${if ((doc.cue?.strokes?.size ?: 0) > 0) cueSvg else """<div class="empty-label">No cue content</div>"""}
  </div>
</div>
<script>
(function() {
  var main = document.getElementById('main-col');
  var cue = document.getElementById('cue-col');
  var syncing = false;
  main.addEventListener('scroll', function() {
    if (syncing) return;
    syncing = true;
    cue.scrollTop = main.scrollTop;
    syncing = false;
  });
  cue.addEventListener('scroll', function() {
    if (syncing) return;
    syncing = true;
    main.scrollTop = cue.scrollTop;
    syncing = false;
  });
})();
</script>
</body>
</html>"""
    }
}
