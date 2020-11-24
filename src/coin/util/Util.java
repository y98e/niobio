package coin.util;

import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.text.*;
import java.util.*;

import org.json.*;

import com.sun.net.httpserver.*;

public abstract class Util {

	public static boolean DEBUG = false;

	public static final SecureRandom random = new SecureRandom();
	public static SimpleDateFormat simpleDateFormat;
	public static Obj conf;

	static {
		try {
			simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			conf = new Obj(Files.readString(Path.of("coin.conf")));
			conf.put("seeds", Util.toStringArray(conf.getArr("seeds")));
			conf.put("startTime", Util.simpleDateFormat.parse(conf.getString("time")).getTime());
			conf.put("snapshot", conf.getString("folderBlocks") + "snapshot.json");

		} catch (IOException | ParseException e) {
			e.printStackTrace();
		}
	}

	public static byte[] bigIntegerToBytes(final BigInteger b, final int numBytes) {
		final byte[] src = b.toByteArray();
		final byte[] dest = new byte[numBytes];
		final boolean isFirstByteOnlyForSign = src[0] == 0;
		final int length = isFirstByteOnlyForSign ? src.length - 1 : src.length;
		final int srcPos = isFirstByteOnlyForSign ? 1 : 0;
		final int destPos = numBytes - length;
		System.arraycopy(src, srcPos, dest, destPos, length);
		return dest;
	}

	public static void cleanFolder(final String dir) {
		final File index = new File(dir);
		if (!index.exists()) {
			index.mkdir();
		} else {
			final String[] entries = index.list();
			for (final String s : entries) {
				final File currentFile = new File(index.getPath(), s);
				currentFile.delete();
			}
		}
	}

//	public static byte[] decodePubKey(final String pubKeyStr) {
//		final byte[] x = Base64.getDecoder().decode(pubKeyStr);
//		final var newArray = new byte[33];
//
//		final var startAt = newArray.length - x.length;
//		System.arraycopy(x, 0, newArray, startAt, x.length);
//		return newArray;
//	}

	public static String deserializeString(final byte[] data) throws IOException, ClassNotFoundException {
		final ByteArrayInputStream in = new ByteArrayInputStream(data);
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	public static Process exec(String command) throws IOException {

		// do NOT update in Debug mode
		if (Util.DEBUG) {
			Util.p("DEBUG IS ON!");
			if (command.equals("git fetch origin") || command.equals("git reset --hard origin/master")) {
				command = "pwd";
			}
		}

		Process p = null;
		ProcessBuilder builder = null;
		Util.p("RUNNING " + command);
		builder = new ProcessBuilder(new String[] { "/bin/bash", "-c", command });
		p = builder.inheritIO().start();
		return p;
	}

	public static Obj inputStreamToJSON(final InputStream inputStream) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int nRead;
		final byte[] data = new byte[1024];
		while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, nRead);
		}

		buffer.flush();
		final byte[] byteArray = buffer.toByteArray();

		final String text = new String(byteArray, StandardCharsets.UTF_8);
		return new Obj(text);
	}

	public static Obj loadObjFromFile(final String fileName) throws IOException, ClassNotFoundException {
		return new Obj(loadStringFromFile(fileName));
	}

	public static String loadStringFromFile(final String fileName) throws IOException, ClassNotFoundException {
		return Files.readString(Paths.get(fileName));
	}

	public static void p(final Object o) {
		System.out.println(
				simpleDateFormat.format(new Date()) + ": " + Thread.currentThread().getName() + ": " + o.toString());
	}

	public static void response(final HttpExchange exchange, final Obj response) throws IOException {
		Util.p("INFO: response: " + response);
		final Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", String.format("application/json; charset=%s", StandardCharsets.UTF_8));
		final byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		final OutputStream os = exchange.getResponseBody();
		os.write(bytes);
		os.flush();
		os.close();
	}

	public static byte[] serialize(final Arr array) throws IOException {
		return array.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] serialize(final Obj obj) throws IOException {
		return obj.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static void startHttpServer(final int port, final HttpHandler handler) throws IOException {
		final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		final HttpContext context = server.createContext("/");
		context.setHandler(handler);
		server.start();
	}

	public static String targetToString(final BigInteger target) {
		return String.format("%64s", target.toString(16)).replace(' ', '0');
	}

	public static String[] toStringArray(final Arr array) {
		if (array == null) return null;

		final String[] arr = new String[array.length()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = array.get(i).toString();
		}
		return arr;
	}

	public static void writeToFile(final String fileName, final Arr array) {
		writeToFile(fileName, array.toString());
	}

	public static void writeToFile(final String fileName, final Obj jobj) {
		writeToFile(fileName, jobj.toString());
	}

	public static void writeToFile(final String fileName, final String text) {
		final File file = new File(fileName);

		if (file.exists()) {
			Util.p("ERROR: File already exists.. not saving");
			return;
		} else {
			file.getParentFile().mkdirs();
		}

		try (PrintWriter out = new PrintWriter(fileName)) {
			out.print(text);
		} catch (final FileNotFoundException e) {
			throw new RuntimeException(e.getMessage());
		}
	}

	static Obj postRPC(final String _url, Obj json) {
		try {
			final URL url = new URL(_url);
			final HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type", "application/json; utf-8");
			con.setRequestProperty("Accept", "application/json");
			con.setConnectTimeout(2500); // set timeout to 2.5 seconds
			con.setDoOutput(true);
			try (OutputStream os = con.getOutputStream()) {
				final byte[] input = json.toString().getBytes("utf-8");
				os.write(input, 0, input.length);
				os.flush();
				os.close();
			}
			try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"))) {
				final StringBuilder response = new StringBuilder();
				String responseLine = null;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				json = new Obj(response.toString());
			}
		} catch (final Exception e) {
			json = new Obj();
			json.put("error", e.getMessage());
		}
		return json;
	}

}
