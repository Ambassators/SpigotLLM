package dev.foreground.spigotllm.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ActiveProcesses {
    private final Map<String, List<ProcessSupport.Running>> active = new HashMap<String, List<ProcessSupport.Running>>();

    synchronized void add(String identity, ProcessSupport.Running running) {
        List<ProcessSupport.Running> list = active.get(identity);
        if (list == null) {
            list = new ArrayList<ProcessSupport.Running>();
            active.put(identity, list);
        }
        list.add(running);
    }

    synchronized void remove(String identity, ProcessSupport.Running running) {
        List<ProcessSupport.Running> list = active.get(identity);
        if (list == null) return;
        list.remove(running);
        if (list.isEmpty()) active.remove(identity);
    }

    synchronized boolean cancel(String identity) {
        List<ProcessSupport.Running> list = active.remove(identity);
        if (list == null || list.isEmpty()) return false;
        for (ProcessSupport.Running running : list) running.destroy();
        return true;
    }

    synchronized void cancelAll() {
        for (List<ProcessSupport.Running> list : active.values()) {
            for (ProcessSupport.Running running : list) running.destroy();
        }
        active.clear();
    }
}
