// GAR: the speech eval's scorer runs Whisper's text through the app's own SpokenForm (gar/router), so
// the eval sees what GAR matches. Reads one text per line on stdin, prints its spoken form per line.
// run.py launches it as a single-file program (java -cp <router classes, dicio-numbers, kotlin-stdlib>
// SpokenFormFilter.java) from the classes the last Gradle build left; nothing is compiled into GAR.
import cc.aaro.gar.router.SpokenForm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class SpokenFormFilter {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            out.println(SpokenForm.INSTANCE.of(line).getSpoken().replace('\n', ' '));
            out.flush();
        }
    }
}
