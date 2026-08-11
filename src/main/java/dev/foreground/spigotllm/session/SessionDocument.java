package dev.foreground.spigotllm.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionDocument {
    List<SessionRecord> sessions = new ArrayList<SessionRecord>();
    Map<String, String> active = new LinkedHashMap<String, String>();
}
