package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import ltsa.ui.EnvConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RankingBased<State, Action> extends PairwiseAbstraction<State, Action> {
    HashMap<String, Integer> componentstatesToRanking;

    public RankingBased(DirectedControllerSynthesisGR1<State, Action> dcs) {
        super(dcs);

        // load ranking information from csv
        String openFileName = EnvConfiguration.getInstance().getOpenFileName();
        String instanceName = openFileName.split("\\.")[0];
        String ranking_info_path = "src/main/resources/models/" + instanceName + "_features.csv";
        componentstatesToRanking = loadCSV(ranking_info_path);
    }

    public RankingBased(DirectedControllerSynthesisGR1<State, Action> dcs, String ranking_info_path) {
        super(dcs);
        componentstatesToRanking = loadCSV(ranking_info_path);
    }

    @Override
    public Boolean isSmaller(Transition<State, Action> t1, Transition<State, Action> t2) {
        Integer ranking1 = componentstatesToRanking.get(t1.getChild().toString());
        Integer ranking2 = componentstatesToRanking.get(t2.getChild().toString());
        return ranking1 < ranking2;
    }

    private HashMap<String, Integer> loadCSV(String filePath) {
        HashMap<String, Integer> componentstatesToRanking = new HashMap<>();
        List<String> lines = null;
        try {
            lines = Files.readAllLines(Paths.get(filePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //remove header
        System.out.println(lines.get(0));
        lines.remove(0);

        for (String line : lines) {
            List<String> components = parseLine(line);

            String stateCompStates = components.get(7);
            Integer stateRanking = getRanking(components.get(3));
            componentstatesToRanking.put(stateCompStates, stateRanking);

            String childCompStates = components.get(8);
            Integer childRanking = getRanking(components.get(4));
            componentstatesToRanking.put(childCompStates, childRanking);
        }
        return componentstatesToRanking;
    }

    private Integer getRanking(String rankings){
        // parse the string "value:25]]"" to get the value
        String ranking = rankings.split(":")[2].split("]")[0];
        return Integer.parseInt(ranking);
    }

    public static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        String regex = "\"([^\"]*)\"|([^,]+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(line);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                result.add(matcher.group(1));
            } else {
                result.add(matcher.group(2));
            }
        }

        return result;
    }
}
