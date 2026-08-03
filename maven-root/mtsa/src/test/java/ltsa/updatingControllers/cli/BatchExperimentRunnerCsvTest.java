package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class BatchExperimentRunnerCsvTest {

    @Test
    public void parsesQuotedCommaQuoteAndNewlineAsOneRecord() {
        String csv = "a,b,c\r\n"
                + "one,\"two,with comma\",\"line1\nline2 \"\"quoted\"\"\"\r\n"
                + "x,y,z\n";

        List<List<String>> records = BatchExperimentRunner.parseCsvRecords(csv);

        assertEquals(3, records.size());
        assertEquals("two,with comma", records.get(1).get(1));
        assertEquals("line1\nline2 \"quoted\"", records.get(1).get(2));
        assertEquals("z", records.get(2).get(2));
    }

    @Test
    public void discardsUnterminatedQuotedRecord() {
        String csv = "a,b\nvalid,row\npartial,\"not finished";

        List<List<String>> records = BatchExperimentRunner.parseCsvRecords(csv);

        assertEquals(2, records.size());
        assertEquals("row", records.get(1).get(1));
    }
}
