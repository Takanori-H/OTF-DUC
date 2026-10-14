package ltsa.lts;

import static ltsa.lts.CompactStateUtils.isProbabilisticMachine;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.Set;

public class PrintTransitions {

  CompactState sm;

  public PrintTransitions(CompactState sm) {
    this.sm = sm;
  }

  public void print(LTSOutput output) {
    print(output, Integer.MAX_VALUE);
  }

  public void print(LTSOutput output, int MAXPRINT) {
    // print name
    output.outln("Process:");
    output.outln("\t" + sm.name);
    // print number of states
    output.outln("States:");
    output.outln("\t" + sm.maxStates);

    output.outln("Transitions:");
    output.out("\t");
    String fsp = getFSP(MAXPRINT);
    output.outln(fsp);
  }



  private void appendAlphabetExtension(StringBuilder fsp) {
    Set<String> unusedLabels = new TreeSet<>();
    Arrays.stream(sm.getAlphabet())
        .filter(label -> !sm.usesLabel(label))
        .forEach(unusedLabels::add);
    if (unusedLabels.isEmpty()) return;
    fsp.append("+{");
    boolean first = true;
    for (String label : unusedLabels) {
      if (!first) fsp.append(", ");
      // Format one label at a time: numeric components require FSP brackets,
      // while a multi-label Alphabet may already contain its own set braces.
      fsp.append(new Alphabet(new String[] {label}).toString());
      first = false;
    }
    fsp.append("}");
  }

  public String getFSP(int k) {
    int linecount = 0;
    StringBuilder fsp = new StringBuilder();

    fsp.append(sm.name).append(" = Q0,");
    fsp.append("\n");

    for (int i = 0; i < sm.maxStates; i++) {
      fsp.append("\tQ").append(i).append("\t= ");
      EventState current;
      boolean isProbabilistic = isProbabilisticMachine(sm);
      if (!isProbabilistic) {
        current = EventStateUtils.transpose(sm.states[i]);
      } else {
        // Output for probabilistic EventStates is not optimised for syntax {Actions} ->
        // Qi
        current = sm.states[i];
      }
      if (current == null) {
        if (i == sm.endseq) fsp.append("END");
        else fsp.append("STOP");
        if (i < sm.maxStates - 1) fsp.append(",\n");
        else {
          appendAlphabetExtension(fsp);
          fsp.append(".\n");
        }
        fsp.append("\n");
      } else {
        fsp.append("(");
        EventState nextList = current;
        while (current != null) {
          linecount++;
          if (linecount > k) {
            fsp.append("EXCEEDED MAXPRINT SETTING\n");
            return fsp.toString();
          }
          if (isProbabilistic) {
            ProbabilisticEventState prob = (ProbabilisticEventState) current;
            int bundle = prob.getBundle();
            int event = prob.getEvent();

            String[] events = new String[1];
            events[0] = sm.alphabet[event];
            Alphabet a = new Alphabet(events);
            fsp.append(a.toString()).append(" -> {");

            while (prob != null) {
              BigDecimal probability = prob.getProbability();
              fsp.append((probability.equals(BigDecimal.valueOf(1))) ? " 1.0" : probability).append(" : Q").append(prob.next);
              prob = prob.probTr;
              if (prob != null) {
                assert (prob.getBundle() == bundle);
                assert (prob.getEvent() == event);
                fsp.append(" + ");
              }
            }

            fsp.append("}");

            // a -> {0.4 : Q14 + 0.6 : Q1}
            if (current.nondet == null) {
              current = nextList.list;
              nextList = current;
            } else {
              current = current.list;
            }
          } else {
            String[] events = EventState.eventsToNext(current, sm.alphabet);
            Alphabet a = new Alphabet(events);
            fsp.append(a.toString()).append(" -> ");
            if (current.next < 0) fsp.append("ERROR");
            else fsp.append("Q").append(current.next);

            current = current.list;
          }

          if (current == null) {
            if (i < sm.maxStates - 1) fsp.append("),\n");
            else {
              fsp.append(")");
              appendAlphabetExtension(fsp);
              fsp.append(".");
            }
          } else {
            fsp.append("\n\t\t  |");
          }
        }
      }
    }
    return fsp.toString();
  }
}
