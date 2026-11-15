# 24FM release shrinking rules.
# Media3, AndroidX and Material ship their own consumer rules; nothing in this app is
# accessed reflectively (the web bridge uses WebMessageListener, not @JavascriptInterface),
# so no additional keep rules are required.
