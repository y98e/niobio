package coin.run;

public abstract class DaemonMiner {

	public static void main(final String[] args) {
		DaemonOnly.main(args);
		MinerOnly.main(args);
	}
}
