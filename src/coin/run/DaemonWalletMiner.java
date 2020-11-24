package coin.run;

public abstract class DaemonWalletMiner {

	public static void main(final String[] args) {
		DaemonOnly.main(args);
		WalletOnly.main(args);
		MinerOnly.main(args);
	}
}
