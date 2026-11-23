package pointcloud;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @Test
    void helpAndVersionGoToStandardOutput() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"--help"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("Usage: pointcloud --help | --version" + System.lineSeparator(), output.toString());
        output.reset();
        assertEquals(0, Main.run(new String[]{"--version"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("pointcloud 0.1.0" + System.lineSeparator(), output.toString());
        assertEquals("", errors.toString());
    }

    @Test
    void invalidCommandReportsAnError() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"--hulp"}, new PrintStream(output), new PrintStream(errors)));
        assertEquals("", output.toString());
        assertEquals("Invalid command. Run pointcloud --help." + System.lineSeparator(), errors.toString());
    }
}
