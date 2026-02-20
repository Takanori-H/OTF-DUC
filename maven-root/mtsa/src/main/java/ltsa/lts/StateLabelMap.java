package ltsa.lts;

import java.util.Hashtable;

public class StateLabelMap {
    // Key: プロセス名 (例: "BATTERY_COUNTER")
    // Value: { 状態ラベル (例: "BAT.0") -> 状態ID (例: 0) }
    public Hashtable<String, Hashtable<String, Integer>> processStateMaps = new Hashtable<>();

    public void addMapping(String processName, String stateLabel, int stateId) {
        Hashtable<String, Integer> map = processStateMaps.get(processName);
        if (map == null) {
            map = new Hashtable<>();
            processStateMaps.put(processName, map);
        }
        map.put(stateLabel, stateId);
    }
    
    public Integer getStateId(String processName, String stateLabel) {
        Hashtable<String, Integer> map = processStateMaps.get(processName);
        if (map == null) return null;
        return map.get(stateLabel);
    }
}