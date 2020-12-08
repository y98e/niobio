module niobio {
	exports coin.wallet;
	exports org.json;
	exports coin.daemon;
	exports coin.miner;
	exports coin.util;

	requires transitive jdk.httpserver;
	requires org.bouncycastle.provider;
	requires java.sql;
	requires org.postgresql.jdbc;
}