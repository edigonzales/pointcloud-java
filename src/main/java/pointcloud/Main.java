package pointcloud;

import java.io.PrintStream;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1) {
            switch (args[0]) {
                case "--help" -> {
                    out.println("Usage: pointcloud --help | --version");
                    return 0;
                }
                case "--version" -> {
                    out.println("pointcloud 0.1.0");
                    return 0;
                }
            }
        }
        err.println("Invalid command. Run pointcloud --help.");
        return 2;
    }
}
