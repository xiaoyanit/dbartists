// Copyright 2010 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// dbartists - Douban artists client for Android
// Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy
// of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
// License for the specific language governing permissions and limitations
// under the License.
//
//
//                           ___====-_  _-====___
//                     _--^^^#####//      \\#####^^^--_
//                  _-^##########// (    ) \\##########^-_
//                 -############//  |\^^/|  \\############-
//               _/############//   (@::@)   \\############\_
//              /#############((     \\//     ))#############\
//             -###############\\    (oo)    //###############-
//            -#################\\  / VV \  //#################-
//           -###################\\/      \//###################-
//          _#/|##########/\######(   /\   )######/\##########|\#_
//          |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
//          `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
//             `   `  `      `   / | |  | | \   '      '  '   '
//                              (  | |  | |  )
//                             __\ | |  | | /__
//                            (vvv(VVV)(VVV)vvv)
//
//                             HERE BE DRAGONS

package org.dbartists;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.StringTokenizer;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.ParseException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.OperatedClientConnection;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.DefaultClientConnection;
import org.apache.http.impl.conn.DefaultClientConnectionOperator;
import org.apache.http.impl.conn.DefaultResponseParser;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.ParserCursor;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.CharArrayBuffer;

import android.util.Log;

public class StreamProxy implements Runnable {
	private class IcyLineParser extends BasicLineParser {
		private static final String ICY_PROTOCOL_NAME = "ICY";

		private IcyLineParser() {
			super();
		}

		@Override
		public boolean hasProtocolVersion(CharArrayBuffer buffer,
				ParserCursor cursor) {
			boolean superFound = super.hasProtocolVersion(buffer, cursor);
			if (superFound) {
				return true;
			}
			int index = cursor.getPos();

			final int protolength = ICY_PROTOCOL_NAME.length();

			if (buffer.length() < protolength)
				return false; // not long enough for "HTTP/1.1"

			if (index < 0) {
				// end of line, no tolerance for trailing whitespace
				// this works only for single-digit major and minor version
				index = buffer.length() - protolength;
			} else if (index == 0) {
				// beginning of line, tolerate leading whitespace
				while ((index < buffer.length())
						&& HTTP.isWhitespace(buffer.charAt(index))) {
					index++;
				}
			} // else within line, don't tolerate whitespace

			if (index + protolength > buffer.length())
				return false;

			return buffer.substring(index, index + protolength).equals(
					ICY_PROTOCOL_NAME);
		}

		@Override
		public Header parseHeader(CharArrayBuffer buffer) throws ParseException {
			return super.parseHeader(buffer);
		}

		@Override
		public ProtocolVersion parseProtocolVersion(CharArrayBuffer buffer,
				ParserCursor cursor) throws ParseException {

			if (buffer == null) {
				throw new IllegalArgumentException(
						"Char array buffer may not be null");
			}
			if (cursor == null) {
				throw new IllegalArgumentException(
						"Parser cursor may not be null");
			}

			final int protolength = ICY_PROTOCOL_NAME.length();

			int indexFrom = cursor.getPos();
			int indexTo = cursor.getUpperBound();

			skipWhitespace(buffer, cursor);

			int i = cursor.getPos();

			// long enough for "HTTP/1.1"?
			if (i + protolength + 4 > indexTo) {
				throw new ParseException("Not a valid protocol version: "
						+ buffer.substring(indexFrom, indexTo));
			}

			// check the protocol name and slash
			if (!buffer.substring(i, i + protolength).equals(ICY_PROTOCOL_NAME)) {
				return super.parseProtocolVersion(buffer, cursor);
			}

			cursor.updatePos(i + protolength);

			return createProtocolVersion(1, 0);
		}

		@Override
		public RequestLine parseRequestLine(CharArrayBuffer buffer,
				ParserCursor cursor) throws ParseException {
			return super.parseRequestLine(buffer, cursor);
		}

		@Override
		public StatusLine parseStatusLine(CharArrayBuffer buffer,
				ParserCursor cursor) throws ParseException {
			StatusLine superLine = super.parseStatusLine(buffer, cursor);
			return superLine;
		}
	}

	class MyClientConnection extends DefaultClientConnection {
		@Override
		protected HttpMessageParser createResponseParser(
				final SessionInputBuffer buffer,
				final HttpResponseFactory responseFactory,
				final HttpParams params) {
			return new DefaultResponseParser(buffer, new IcyLineParser(),
					responseFactory, params);
		}
	}

	class MyClientConnectionOperator extends DefaultClientConnectionOperator {
		public MyClientConnectionOperator(final SchemeRegistry sr) {
			super(sr);
		}

		@Override
		public OperatedClientConnection createConnection() {
			return new MyClientConnection();
		}
	}

	class MyClientConnManager extends SingleClientConnManager {
		private MyClientConnManager(HttpParams params, SchemeRegistry schreg) {
			super(params, schreg);
		}

		@Override
		protected ClientConnectionOperator createConnectionOperator(
				final SchemeRegistry sr) {
			return new MyClientConnectionOperator(sr);
		}
	}

	private static final String LOG_TAG = StreamProxy.class.getName();

	private static final String CACHE_BASE = "/sdcard/dbartists/";

	private static String convertToHex(byte[] data) {
		StringBuffer buf = new StringBuffer();
		for (int i = 0; i < data.length; i++) {
			int halfbyte = (data[i] >>> 4) & 0x0F;
			int two_halfs = 0;
			do {
				if ((0 <= halfbyte) && (halfbyte <= 9))
					buf.append((char) ('0' + halfbyte));
				else
					buf.append((char) ('a' + (halfbyte - 10)));
				halfbyte = data[i] & 0x0F;
			} while (two_halfs++ < 1);
		}
		return buf.toString();
	}

	public static String getFileName(String name) {
		String file;
		try {
			file = MD5(name);
		} catch (NoSuchAlgorithmException e) {
			file = null;
		} catch (UnsupportedEncodingException e) {
			file = null;
		}
		if (file != null)
			return CACHE_BASE + file + ".mp3";
		else
			return CACHE_BASE + "tmp.mp3";

	}

	public static String MD5(String text) throws NoSuchAlgorithmException,
			UnsupportedEncodingException {
		MessageDigest md;
		md = MessageDigest.getInstance("MD5");
		byte[] md5hash = new byte[32];
		md.update(text.getBytes("UTF-8"), 0, text.length());
		md5hash = md.digest();
		return convertToHex(md5hash);
	}

	private int port = 0;

	private String title = "tmp";

	private volatile boolean isRunning = true;

	private ServerSocket socket;

	private Thread thread;

	private HttpResponse download(String url) {
		DefaultHttpClient seed = new DefaultHttpClient();
		SchemeRegistry registry = new SchemeRegistry();
		registry.register(new Scheme("http", PlainSocketFactory
				.getSocketFactory(), 80));
		SingleClientConnManager mgr = new MyClientConnManager(seed.getParams(),
				registry);
		DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());
		HttpGet method = new HttpGet(url);
		HttpResponse response = null;
		try {
			Log.d(LOG_TAG, "starting download");
			response = http.execute(method);
			Log.d(LOG_TAG, "downloaded");
		} catch (ClientProtocolException e) {
			Log.e(LOG_TAG, "Error downloading", e);
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error downloading", e);
		}
		return response;
	}

	public boolean getIsRunning() {
		return isRunning;
	}

	public int getPort() {
		return port;
	}

	public void init() {
		try {
			socket = new ServerSocket(port, 0,
					InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
			socket.setSoTimeout(5000);
			port = socket.getLocalPort();
			Log.d(LOG_TAG, "port " + port + " obtained");
		} catch (UnknownHostException e) {
			Log.e(LOG_TAG, "Error initializing server", e);
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error initializing server", e);
		}
	}

	private void processRequest(HttpRequest request, Socket client)
			throws IllegalStateException, IOException {
		if (request == null) {
			return;
		}
		Log.d(LOG_TAG, "processing");
		String url = request.getRequestLine().getUri();

		String cache = getFileName(title);
		File f = new File(cache);
		if (!f.exists())
			f.createNewFile();

		HttpResponse realResponse = download(url);
		if (realResponse == null) {
			return;
		}

		Log.d(LOG_TAG, "downloading...");

		InputStream data = realResponse.getEntity().getContent();

		int totalFileSize = -1;
		for (Header h : realResponse.getAllHeaders()) {
			if (h.getName().toLowerCase().equals("content-length"))
				try {
					totalFileSize = Integer.valueOf(h.getValue());
				} catch (NumberFormatException e) {
					// Ignore
				}
		}
		StringBuilder httpString = new StringBuilder();
		httpString.append("HTTP/1.1 200 OK\r\n");
		httpString.append("Content-Type: audio/mpeg\r\n");
		httpString.append("Connection: close\r\n");
		httpString.append("Accept-Ranges: bytes\r\n");
		if (totalFileSize != -1)
			httpString.append("Content-Length: " + totalFileSize + "\r\n");
		httpString.append("Content-Disposition: inline; filename=" + cache
				+ ".mp3\r\n\r\n");

		Log.d(LOG_TAG, "headers done");

		OutputStream output = new FileOutputStream(cache);

		try {
			byte[] buffer = httpString.toString().getBytes();
			int readBytes;
			Log.d(LOG_TAG, "writing to client");
			client.getOutputStream().write(buffer, 0, buffer.length);

			// Start streaming content.
			byte[] buff = new byte[1024 * 250];
			while (isRunning
					&& (readBytes = data.read(buff, 0, buff.length)) != -1) {
				client.getOutputStream().write(buff, 0, readBytes);
				output.write(buff, 0, readBytes);
				client.getOutputStream().flush();
				output.flush();
			}
		} catch (Exception e) {
			Log.e("", e.getMessage(), e);
		} finally {
			if (data != null) {
				data.close();
			}
			if (output != null) {
				output.close();
			}
			client.close();
		}
	}

	private HttpRequest readRequest(Socket client) {
		HttpRequest request = null;
		InputStream is;
		String firstLine;
		try {
			is = client.getInputStream();
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(is), 8192);
			firstLine = reader.readLine();
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error parsing request", e);
			return request;
		}

		if (firstLine == null) {
			Log.i(LOG_TAG, "Proxy client closed connection without a request.");
			return request;
		}

		StringTokenizer st = new StringTokenizer(firstLine);
		String method = st.nextToken();
		String uri = st.nextToken();
		Log.d(LOG_TAG, uri);
		String realUri = uri.substring(1);
		Log.d(LOG_TAG, realUri);
		request = new BasicHttpRequest(method, realUri);
		return request;
	}

	@Override
	public void run() {
		Log.d(LOG_TAG, "running");
		while (isRunning) {
			try {
				Socket client = socket.accept();
				if (client == null) {
					continue;
				}
				Log.d(LOG_TAG, "client connected");
				HttpRequest request = readRequest(client);
				processRequest(request, client);
			} catch (SocketTimeoutException e) {
				// Do nothing
			} catch (IOException e) {
				Log.e(LOG_TAG, "Error connecting to client", e);
			}
		}
		Log.d(LOG_TAG, "Proxy interrupted. Shutting down.");
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public void start() {

		if (socket == null) {
			throw new IllegalStateException(
					"Cannot start proxy; it has not been initialized.");
		}

		thread = new Thread(this);
		thread.start();
	}

	public void stop() {
		isRunning = false;

		if (thread == null) {
			throw new IllegalStateException(
					"Cannot stop proxy; it has not been started.");
		}

		thread.interrupt();
		try {
			thread.join(5000);
		} catch (InterruptedException e) {
			Log.e(LOG_TAG, "join error");
		}

		try {
			socket.close();
		} catch (IOException e) {
			// Nothing
		}
	}

}
