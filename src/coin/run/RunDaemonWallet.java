package coin.run;

public abstract class RunDaemonWallet extends Run {

	// Daemon + Wallet. Git update and run it again.
	public static void main(final String[] args) {
		run("coin.run.DaemonWallet");
	}
}
