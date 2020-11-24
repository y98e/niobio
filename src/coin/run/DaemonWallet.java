package coin.run;

public abstract class DaemonWallet {

	public static void main(final String[] args) {
		DaemonOnly.main(args);
		WalletOnly.main(args);
	}
}
