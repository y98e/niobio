package coin.run;

public abstract class RunDaemon extends Run {

	// git update and run it again
	public static void main(final String[] args) {
		run("coin.daemon.Daemon");
	}
}
