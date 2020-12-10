package coin.run;

public abstract class RunDaemon extends Run {

	public static void main(final String[] args) {
		run("coin.daemon.Daemon");
	}
}
