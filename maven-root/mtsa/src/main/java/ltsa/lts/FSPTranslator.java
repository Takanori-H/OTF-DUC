package ltsa.lts;

public class FSPTranslator extends AbstractTranslator {


    @Override
    protected void doTranslate() {
        CompactState composition = base_composite.composition;
        PrintTransitions transitions = new PrintTransitions(composition);
        String fsp = transitions.getFSP(Integer.MAX_VALUE);
        add(fsp);
    }
}
