/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.json.JSONException;
import org.json.JSONObject;

import com.droidboosters.empowerwomen.R;

import org.odk.collect.android.activities.XMLParser;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.listeners.InstanceUploaderListener;
import org.odk.collect.android.logic.FormController;
import org.odk.collect.android.logic.PropertyManager;
import org.odk.collect.android.preferences.PreferencesActivity;
import org.odk.collect.android.provider.InstanceProviderAPI;
import org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns;
import org.odk.collect.android.utilities.WebUtils;
import org.opendatakit.httpclientandroidlib.Header;
import org.opendatakit.httpclientandroidlib.HttpEntity;
import org.opendatakit.httpclientandroidlib.HttpResponse;
import org.opendatakit.httpclientandroidlib.HttpStatus;
import org.opendatakit.httpclientandroidlib.client.ClientProtocolException;
import org.opendatakit.httpclientandroidlib.client.HttpClient;
import org.opendatakit.httpclientandroidlib.client.methods.HttpGet;
import org.opendatakit.httpclientandroidlib.client.methods.HttpHead;
import org.opendatakit.httpclientandroidlib.client.methods.HttpPost;
import org.opendatakit.httpclientandroidlib.client.methods.HttpUriRequest;
import org.opendatakit.httpclientandroidlib.conn.ConnectTimeoutException;
import org.opendatakit.httpclientandroidlib.conn.HttpHostConnectException;
import org.opendatakit.httpclientandroidlib.entity.mime.MultipartEntity;
import org.opendatakit.httpclientandroidlib.entity.mime.content.FileBody;
import org.opendatakit.httpclientandroidlib.entity.mime.content.StringBody;
import org.opendatakit.httpclientandroidlib.impl.client.DefaultHttpClient;
import org.opendatakit.httpclientandroidlib.protocol.HttpContext;
import org.opendatakit.httpclientandroidlib.util.EntityUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

/**
 * Background task for uploading completed forms.
 * 
 * @author Carl Hartung (carlhartung@gmail.com)
 */
public class InstanceUploaderTask extends
		AsyncTask<Long, Integer, InstanceUploaderTask.Outcome> {

	private static final String t = "InstanceUploaderTask";
	// it can take up to 27 seconds to spin up Aggregate
	private static final int CONNECTION_TIMEOUT = 60000;
	private static final String fail = "Error: ";

	String name = "";
	String age = "";
	String village = "";
	String state = "";
	String no_of_mem = "";
	String female_mem = "";
	String salary = "";
	String volunteer = "";
	String availability = "";
	String volunteer_past = "";
	String date = "";
	String photo = "";
	
	String xml_id = "", uuid = "";

	private InstanceUploaderListener mStateListener;

	public static class Outcome {
		public Uri mAuthRequestingServer = null;
		public HashMap<String, String> mResults = new HashMap<String, String>();
	}

	private String readFile(String fileName) {
		StringBuffer datax = new StringBuffer("");
		try {
			FileInputStream fIn = Collect.getInstance().openFileInput(fileName);
			InputStreamReader isr = new InputStreamReader(fIn);
			BufferedReader buffreader = new BufferedReader(isr);

			String readString = buffreader.readLine();
			while (readString != null) {
				datax.append(readString);
				readString = buffreader.readLine();
			}

			isr.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		System.out.println("file content " + datax.toString());

		return datax.toString();
	}

	public Document getDomElement(String xml) {
		Document doc = null;
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		try {

			DocumentBuilder db = dbf.newDocumentBuilder();

			InputSource is = new InputSource();
			is.setCharacterStream(new StringReader(xml));
			doc = db.parse(is);

		} catch (ParserConfigurationException e) {
			Log.e("Error: ", e.getMessage());
			return null;
		} catch (SAXException e) {
			Log.e("Error: ", e.getMessage());
			return null;
		} catch (IOException e) {
			Log.e("Error: ", e.getMessage());
			return null;
		}
		// return DOM
		return doc;
	}

	public String getValue(Element item, String str) {
		NodeList n = item.getElementsByTagName(str);
		return this.getElementValue(n.item(0));
	}

	public final String getElementValue(Node elem) {
		Node child;
		if (elem != null) {
			if (elem.hasChildNodes()) {
				for (child = elem.getFirstChild(); child != null; child = child
						.getNextSibling()) {
					if (child.getNodeType() == Node.TEXT_NODE) {
						return child.getNodeValue();
					}
				}
			}
		}
		return "";
	}

	/**
	 * Uploads to urlString the submission identified by id with filepath of
	 * instance
	 * 
	 * @param urlString
	 *            destination URL
	 * @param id
	 * @param instanceFilePath
	 * @param toUpdate
	 *            - Instance URL for recording status update.
	 * @param httpclient
	 *            - client connection
	 * @param localContext
	 *            - context (e.g., credentials, cookies) for client connection
	 * @param uriRemap
	 *            - mapping of Uris to avoid redirects on subsequent invocations
	 * @return false if credentials are required and we should terminate
	 *         immediately.
	 */
	private boolean uploadOneSubmission(String urlString, String id,
			String instanceFilePath, Uri toUpdate, HttpContext localContext,
			Map<Uri, Uri> uriRemap, Outcome outcome) {

		Collect.getInstance().getActivityLogger()
				.logAction(this, urlString, instanceFilePath);

		ContentValues cv = new ContentValues();
		Uri u = Uri.parse(urlString);
		HttpClient httpclient = WebUtils.createHttpClient(CONNECTION_TIMEOUT);

		System.out.println(" print me please");

		boolean openRosaServer = false;
		if (uriRemap.containsKey(u)) {
			// we already issued a head request and got a response,
			// so we know the proper URL to send the submission to
			// and the proper scheme. We also know that it was an
			// OpenRosa compliant server.
			openRosaServer = true;
			u = uriRemap.get(u);

			// if https then enable preemptive basic auth...
			if (u.getScheme().equals("https")) {
				WebUtils.enablePreemptiveBasicAuth(localContext, u.getHost());
			}

			Log.i(t,
					"Using Uri remap for submission " + id + ". Now: "
							+ u.toString());
		} else {

			// if https then enable preemptive basic auth...
			if (u.getScheme().equals("https")) {
				WebUtils.enablePreemptiveBasicAuth(localContext, u.getHost());
			}

			// we need to issue a head request
			HttpHead httpHead = WebUtils.createOpenRosaHttpHead(u);

			// prepare response
			HttpResponse response = null;
			try {
				Log.i(t,
						"Issuing HEAD request for " + id + " to: "
								+ u.toString());

				response = httpclient.execute(httpHead, localContext);
				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
					// clear the cookies -- should not be necessary?
					Collect.getInstance().getCookieStore().clear();

					WebUtils.discardEntityBytes(response);
					// we need authentication, so stop and return what we've
					// done so far.
					outcome.mAuthRequestingServer = u;
					return false;
				} else if (statusCode == 204) {
					Header[] locations = response.getHeaders("Location");
					WebUtils.discardEntityBytes(response);
					if (locations != null && locations.length == 1) {
						try {
							Uri uNew = Uri.parse(URLDecoder.decode(
									locations[0].getValue(), "utf-8"));
							if (u.getHost().equalsIgnoreCase(uNew.getHost())) {
								openRosaServer = true;
								// trust the server to tell us a new location
								// ... and possibly to use https instead.
								uriRemap.put(u, uNew);
								u = uNew;
							} else {
								// Don't follow a redirection attempt to a
								// different host.
								// We can't tell if this is a spoof or not.
								outcome.mResults
										.put(id,
												fail
														+ "Unexpected redirection attempt to a different host: "
														+ uNew.toString());
								cv.put(InstanceColumns.STATUS,
										InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
								Collect.getInstance().getContentResolver()
										.update(toUpdate, cv, null, null);
								return true;
							}
						} catch (Exception e) {
							e.printStackTrace();
							outcome.mResults.put(id,
									fail + urlString + " " + e.toString());
							cv.put(InstanceColumns.STATUS,
									InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
							Collect.getInstance().getContentResolver()
									.update(toUpdate, cv, null, null);
							return true;
						}
					}
				} else {
					// may be a server that does not handle
					WebUtils.discardEntityBytes(response);

					Log.w(t, "Status code on Head request: " + statusCode);
					if (statusCode >= HttpStatus.SC_OK
							&& statusCode < HttpStatus.SC_MULTIPLE_CHOICES) {
						outcome.mResults
								.put(id,
										fail
												+ "Invalid status code on Head request.  If you have a web proxy, you may need to login to your network. ");
						cv.put(InstanceColumns.STATUS,
								InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
						Collect.getInstance().getContentResolver()
								.update(toUpdate, cv, null, null);
						return true;
					}
				}
			} catch (ClientProtocolException e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + "Client Protocol Exception");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			} catch (ConnectTimeoutException e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + "Connection Timeout");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			} catch (UnknownHostException e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + e.toString()
						+ " :: Network Connection Failed");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			} catch (SocketTimeoutException e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + "Connection Timeout");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			} catch (HttpHostConnectException e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + "Network Connection Refused");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			} catch (Exception e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id, fail + "Generic Exception");
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			}
		}

		// At this point, we may have updated the uri to use https.
		// This occurs only if the Location header keeps the host name
		// the same. If it specifies a different host name, we error
		// out.
		//
		// And we may have set authentication cookies in our
		// cookiestore (referenced by localContext) that will enable
		// authenticated publication to the server.
		//
		// get instance file
		File instanceFile = new File(instanceFilePath);

		// Under normal operations, we upload the instanceFile to
		// the server. However, during the save, there is a failure
		// window that may mark the submission as complete but leave
		// the file-to-be-uploaded with the name "submission.xml" and
		// the plaintext submission files on disk. In this case,
		// upload the submission.xml and all the files in the directory.
		// This means the plaintext files and the encrypted files
		// will be sent to the server and the server will have to
		// figure out what to do with them.
		File submissionFile = new File(instanceFile.getParentFile(),
				"submission.xml");
		if (submissionFile.exists()) {
			Log.w(t, "submission.xml will be uploaded instead of "
					+ instanceFile.getAbsolutePath());
		} else {
			submissionFile = instanceFile;
		}

		// readFile(submissionFile);

		File sdcard = Environment.getExternalStorageDirectory();

		// Get the text file
		File file = submissionFile;

		// Read text from file
		StringBuilder text = new StringBuilder();

		try {
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line;

			while ((line = br.readLine()) != null) {
				text.append(line);
				text.append('\n');
			}
		} catch (IOException e) {
			// You'll need to add proper error handling here
			e.printStackTrace();
		}

		System.out.println("print text " + text.toString());
		XMLParser parser = new XMLParser();
		Document doc = parser.getDomElement(text.toString()); // getting DOM
																// element

		doc.getDocumentElement().normalize();
		NodeList nl = doc.getElementsByTagName("data");
		
	//	System.out.println("all my id  "+nl.item(0).getAttributes().toString());
		
		int len = nl.getLength();
		for (int i = 0; i < nl.getLength(); i++) {
			//Element data = (Element) nl.item(i);
			//id = data.getAttribute("id");
			
			
			Node nNode = nl.item(i);
			
			Element edata = (Element)nNode;
			
			xml_id = edata.getAttribute("id");
			
			System.out.println("all my id  "+edata.getAttribute("id")+"   "+edata.getNodeName()+"  "+edata.getNodeValue()+"  "+edata.getTagName()+"  "+edata.getTextContent());
		}
		
		//Element mynode = (Element) nl.item(0);
		
		NodeList myList = doc.getElementsByTagName("meta");
		Element mydata = (Element) nl.item(0);
		//NodeList mylist2 = mydata.getTextContent();
		//uuid = ((Node) mylist2.item(0)).getNodeValue();
		uuid= mydata.getTextContent();
				
	//	id = mynode

		// looping through all item nodes <item>
		//for (int i = 0; i < nl.getLength(); i++) {
			name = parser.getValue((Element) nl.item(0), "name"); // name child
																	// value
			age = parser.getValue((Element) nl.item(0), "age_limit"); // cost
																		// child
																		// value
			village = parser.getValue((Element) nl.item(0), "village_name"); // description
																				// child
																				// value
			state = parser.getValue((Element) nl.item(0), "state");
			no_of_mem = parser.getValue((Element) nl.item(0),
					"number_of_family_members");
			female_mem = parser.getValue((Element) nl.item(0),
					"females_number_family");
			salary = parser.getValue((Element) nl.item(0), "income");
			volunteer = parser.getValue((Element) nl.item(0), "volunteer_for");
			availability = parser
					.getValue((Element) nl.item(0), "availability");
			volunteer_past = parser.getValue((Element) nl.item(0),
					"volunteer_in_past");
			date = parser.getValue((Element) nl.item(0), "current_date");
			photo = parser.getValue((Element) nl.item(0), "photo");
		//}

			
			
		new	GetRestAPIResponse().execute(name, village,  volunteer_past );
		
		
		
		
		if (!instanceFile.exists() && !submissionFile.exists()) {
			outcome.mResults
					.put(id, fail + "instance XML file does not exist!");
			cv.put(InstanceColumns.STATUS,
					InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
			Collect.getInstance().getContentResolver()
					.update(toUpdate, cv, null, null);
			return true;
		}

		// find all files in parent directory
		File[] allFiles = instanceFile.getParentFile().listFiles();

		// add media files
		List<File> files = new ArrayList<File>();
		for (File f : allFiles) {
			String fileName = f.getName();

			int dotIndex = fileName.lastIndexOf(".");
			String extension = "";
			if (dotIndex != -1) {
				extension = fileName.substring(dotIndex + 1);
			}

			if (fileName.startsWith(".")) {
				// ignore invisible files
				continue;
			}
			if (fileName.equals(instanceFile.getName())) {
				continue; // the xml file has already been added
			} else if (fileName.equals(submissionFile.getName())) {
				continue; // the xml file has already been added
			} else if (openRosaServer) {
				files.add(f);
			} else if (extension.equals("jpg")) { // legacy 0.9x
				files.add(f);
			} else if (extension.equals("3gpp")) { // legacy 0.9x
				files.add(f);
			} else if (extension.equals("3gp")) { // legacy 0.9x
				files.add(f);
			} else if (extension.equals("mp4")) { // legacy 0.9x
				files.add(f);
			} else {
				Log.w(t, "unrecognized file type " + f.getName());
			}
		}

		boolean first = true;
		int j = 0;
		int lastJ;
		while (j < files.size() || first) {
			lastJ = j;
			first = false;

			HttpPost httppost = WebUtils.createOpenRosaHttpPost(u);

			MimeTypeMap m = MimeTypeMap.getSingleton();

			long byteCount = 0L;

			// mime post
			MultipartEntity entity = new MultipartEntity();

			// add the submission file first...
			FileBody fb = new FileBody(submissionFile, "text/xml");
			entity.addPart("xml_submission_file", fb);

			// readFile(submissionFile.getName());

			Log.i(t, "added xml_submission_file: " + files.size() + "    "
					+ submissionFile.getName() + "  ");
			byteCount += submissionFile.length();

			for (; j < files.size(); j++) {
				File f = files.get(j);
				String fileName = f.getName();
				int idx = fileName.lastIndexOf(".");
				String extension = "";
				if (idx != -1) {
					extension = fileName.substring(idx + 1);
				}
				String contentType = m.getMimeTypeFromExtension(extension);

				// we will be processing every one of these, so
				// we only need to deal with the content type determination...
				if (extension.equals("xml")) {
					fb = new FileBody(f, "text/xml");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added xml file " + f.getName());

				} else if (extension.equals("jpg")) {
					fb = new FileBody(f, "image/jpeg");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added image file " + f.getName());
				} else if (extension.equals("3gpp")) {
					fb = new FileBody(f, "audio/3gpp");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added audio file " + f.getName());
				} else if (extension.equals("3gp")) {
					fb = new FileBody(f, "video/3gpp");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added video file " + f.getName());
				} else if (extension.equals("mp4")) {
					fb = new FileBody(f, "video/mp4");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added video file " + f.getName());
				} else if (extension.equals("csv")) {
					fb = new FileBody(f, "text/csv");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added csv file " + f.getName());
				} else if (f.getName().endsWith(".amr")) {
					fb = new FileBody(f, "audio/amr");
					entity.addPart(f.getName(), fb);
					Log.i(t, "added audio file " + f.getName());
				} else if (extension.equals("xls")) {
					fb = new FileBody(f, "application/vnd.ms-excel");
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added xls file " + f.getName());
				} else if (contentType != null) {
					fb = new FileBody(f, contentType);
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.i(t, "added recognized filetype (" + contentType + ") "
							+ f.getName());
				} else {
					contentType = "application/octet-stream";
					fb = new FileBody(f, contentType);
					entity.addPart(f.getName(), fb);
					byteCount += f.length();
					Log.w(t, "added unrecognized file (" + contentType + ") "
							+ f.getName());
				}

				// System.out.println("file content "+readFile(f.getName()));

				// we've added at least one attachment to the request...
				if (j + 1 < files.size()) {
					if ((j - lastJ + 1 > 100)
							|| (byteCount + files.get(j + 1).length() > 10000000L)) {
						// the next file would exceed the 10MB threshold...
						Log.i(t,
								"Extremely long post is being split into multiple posts");
						try {
							StringBody sb = new StringBody("yes",
									Charset.forName("UTF-8"));
							entity.addPart("*isIncomplete*", sb);
						} catch (Exception e) {
							e.printStackTrace(); // never happens...
						}
						++j; // advance over the last attachment added...
						break;
					}
				}
			}

			httppost.setEntity(entity);

			// prepare response and return uploaded
			HttpResponse response = null;
			try {
				Log.i(t,
						"Issuing POST request for " + id + " to: "
								+ u.toString());
				response = httpclient.execute(httppost, localContext);
				int responseCode = response.getStatusLine().getStatusCode();
				WebUtils.discardEntityBytes(response);

				Log.i(t, "Response code:" + responseCode);
				// verify that the response was a 201 or 202.
				// If it wasn't, the submission has failed.
				if (responseCode != HttpStatus.SC_CREATED
						&& responseCode != HttpStatus.SC_ACCEPTED) {
					if (responseCode == HttpStatus.SC_OK) {
						outcome.mResults.put(id, fail
								+ "Network login failure? Again?");
					} else if (responseCode == HttpStatus.SC_UNAUTHORIZED) {
						// clear the cookies -- should not be necessary?
						Collect.getInstance().getCookieStore().clear();
						outcome.mResults.put(id, fail
								+ response.getStatusLine().getReasonPhrase()
								+ " (" + responseCode + ") at " + urlString);
					} else {
						outcome.mResults.put(id, fail
								+ response.getStatusLine().getReasonPhrase()
								+ " (" + responseCode + ") at " + urlString);
					}
					cv.put(InstanceColumns.STATUS,
							InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
					Collect.getInstance().getContentResolver()
							.update(toUpdate, cv, null, null);
					return true;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Log.e(t, e.toString());
				WebUtils.clearHttpConnectionManager();
				outcome.mResults.put(id,
						fail + "Generic Exception. " + e.toString());
				cv.put(InstanceColumns.STATUS,
						InstanceProviderAPI.STATUS_SUBMISSION_FAILED);
				Collect.getInstance().getContentResolver()
						.update(toUpdate, cv, null, null);
				return true;
			}
		}

		// if it got here, it must have worked
		outcome.mResults.put(id,
				Collect.getInstance().getString(R.string.success));
		cv.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_SUBMITTED);
		Collect.getInstance().getContentResolver()
				.update(toUpdate, cv, null, null);
		return true;
	}
	
	
	private class GetRestAPIResponse extends AsyncTask<String, Void, ArrayList<String>> {

		String language = "";
		
		
		@Override
		protected ArrayList<String> doInBackground(String... params) {
			
			ArrayList<String> data =  new ArrayList<String>();
			
			if(Collect.getInstance().language_selection.equals("hin"))
				language = "hindi";
			if(Collect.getInstance().language_selection.equals("tam"))
				language = "tamil";

			try {

				HttpClient client = new DefaultHttpClient();

				String getURL = "http://transliteration.reverie.co.in/api/processString?lang="+language+"&type=5&inString=";
			
				//String getURL = "http://transliteration.reverie.co.in/api/processString";
				
				
			
				for(int i=0;i<params.length;i++)
				{
					HttpGet get = new HttpGet(getURL+params[i]);
					
					System.out.println("translate result before "+getURL+params[i]);
					
					
					get.setHeader("REV-API-KEY",
							"!REVTESTINGAPI!sdfg");
					
					HttpResponse responseGet = client.execute(get);
					HttpEntity resEntityGet = responseGet.getEntity();
					//Toast.makeText(getApplicationContext(), "hello 2", Toast.LENGTH_SHORT).show();
					String getResponse = EntityUtils.toString(resEntityGet);
					
					
					// Convert String to json object
					JSONObject json = new JSONObject(getResponse);

					// get name from object
					String name_response = json.getString("response");
					data.add(name_response);
					
					
					System.out.println("translate result "+name_response);
					
				}
				
				
				
				
				
				return data;

			} catch (Exception e) {
				//Toast.makeText(getApplicationContext(), "hello 3", Toast.LENGTH_SHORT).show();
				e.printStackTrace();
			}
			return data;

			//return null;
		}
		
		
		
		
		@Override
		protected void onPostExecute(ArrayList<String> result)
		{
			
//			<?xml version='1.0' ?><data id="build_Rural-Programme-Volunteer_1393053003"><meta><instanceID>
//			uuid:5a4ae000-afa6-4bce-8788-bf23e76f95ae</instanceID></meta><name>Ghh</name><age_limit>26-40</age_limit><village_name>Jinkebachalli
//			</village_name><state>Tamil Nadu</state><number_of_family_members>5</number_of_family_members><females_number_family>5</females_number_family>
//			<income>6</income><volunteer_for>Help support the organisation's initiative</volunteer_for><availability>Weekend evenings</availability>
//			<volunteer_in_past>Yes</volunteer_in_past><current_date>2014-02-22</current_date><photo>1393060264129.jpg</photo></data>
			
			
			String xml_file = "";
			
			xml_file += "<?xml version='1.0' ?><data id=";
			xml_file += xml_id;
			xml_file += "><meta><instanceID>";
			xml_file += uuid + "</instanceID></meta><name>";
			xml_file += result.get(0);
			xml_file += "</name><age_limit>";
			xml_file += age+ "</age_limit><village_name>";
			xml_file += result.get(1)+"</village_name><state>";
			xml_file += state+"</state><number_of_family_members>";
			xml_file += no_of_mem+ "</number_of_family_members>";
			xml_file += "<females_number_family>"+female_mem+"</females_number_family>";
			xml_file += "<income>"+salary+"</income>";
			xml_file += "<volunteer_for>"+volunteer+"</volunteer_for>";
			xml_file += "<availability>"+availability+"</availability>";
			xml_file += "<volunteer_in_past>"+result.get(2)+"</volunteer_in_past>";
			xml_file +=  "<current_date>"+date+"</current_date>";
			xml_file +=  "<photo>"+photo+"</photo></data>";		
			
			System.out.println("xml result "+xml_file);
			
			
			try {
				stringToDom(xml_file);
			} catch (SAXException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ParserConfigurationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			
			
			
		}
		
	}
	
	
	public static void stringToDom(String xmlSource) 
	        throws SAXException, ParserConfigurationException, IOException {
	    // Parse the given input
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	    DocumentBuilder builder = factory.newDocumentBuilder();
	    Document doc = builder.parse(new InputSource(new StringReader(xmlSource)));

	    // Write the parsed document to an xml file
	    TransformerFactory transformerFactory = TransformerFactory.newInstance();
	    Transformer transformer;
		try {
			transformer = transformerFactory.newTransformer();
		
	    DOMSource source = new DOMSource(doc);

	    StreamResult result =  new StreamResult(new File("submission.xml"));
	    transformer.transform(source, result);
	    
		} catch (TransformerConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TransformerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	// TODO: This method is like 350 lines long, down from 400.
	// still. ridiculous. make it smaller.
	protected Outcome doInBackground(Long... values) {
		Outcome outcome = new Outcome();

		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(Collect.getInstance());

		int language = settings.getInt("lang", 0);

		FormController formController = Collect.getInstance()
				.getFormController();

		// System.out.println("language final "+formController.getLanguage());

		// if(language == 1)
		// {

		String getURL = "http://transliteration.reverie.co.in/api/processString?lang=hindi&type=5&inString=karthi";

		try {
			HttpUriRequest request = new HttpGet(getURL);
			request.setHeader("REV-API-KEY", "!REVTESTINGAPI!sdfg");
			HttpClient client = new DefaultHttpClient();
			HttpResponse httpResponse = client.execute(request);

			HttpEntity resEntityGet = httpResponse.getEntity();
			// Toast.makeText(getApplicationContext(), "hello 2",
			// Toast.LENGTH_SHORT).show();
			String getResponse = EntityUtils.toString(resEntityGet);
			System.out.println("lang selectiopn "
					+ Collect.getInstance().language_selection);

			// Convert String to json object
			JSONObject json = new JSONObject(getResponse);

			// get name from object
			String name_response = json.getString("response");

			System.out.println("api  result" + name_response);

		} catch (ClientProtocolException e) {

			e.printStackTrace();
		} catch (IOException e) {

			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// }

		if (language == 2) {

		}

		String selection = InstanceColumns._ID + "=?";
		String[] selectionArgs = new String[(values == null) ? 0
				: values.length];
		if (values != null) {
			for (int i = 0; i < values.length; i++) {
				if (i != values.length - 1) {
					selection += " or " + InstanceColumns._ID + "=?";
				}
				selectionArgs[i] = values[i].toString();
			}
		}

		String deviceId = new PropertyManager(Collect.getInstance()
				.getApplicationContext())
				.getSingularProperty(PropertyManager.OR_DEVICE_ID_PROPERTY);

		// get shared HttpContext so that authentication and cookies are
		// retained.
		HttpContext localContext = Collect.getInstance().getHttpContext();

		Map<Uri, Uri> uriRemap = new HashMap<Uri, Uri>();

		Cursor c = null;
		try {
			c = Collect
					.getInstance()
					.getContentResolver()
					.query(InstanceColumns.CONTENT_URI, null, selection,
							selectionArgs, null);

			if (c.getCount() > 0) {
				c.moveToPosition(-1);
				while (c.moveToNext()) {
					if (isCancelled()) {
						return outcome;
					}
					publishProgress(c.getPosition() + 1, c.getCount());
					String instance = c
							.getString(c
									.getColumnIndex(InstanceColumns.INSTANCE_FILE_PATH));
					String id = c.getString(c
							.getColumnIndex(InstanceColumns._ID));
					Uri toUpdate = Uri.withAppendedPath(
							InstanceColumns.CONTENT_URI, id);

					int subIdx = c
							.getColumnIndex(InstanceColumns.SUBMISSION_URI);
					String urlString = c.isNull(subIdx) ? null : c
							.getString(subIdx);
					if (urlString == null) {
						// SharedPreferences settings =
						// PreferenceManager.getDefaultSharedPreferences(Collect.getInstance());
						urlString = settings.getString(
								PreferencesActivity.KEY_SERVER_URL,
								Collect.getInstance().getString(
										R.string.default_server_url));
						if (urlString.charAt(urlString.length() - 1) == '/') {
							urlString = urlString.substring(0,
									urlString.length() - 1);
						}
						// NOTE: /submission must not be translated! It is the
						// well-known path on the server.
						String submissionUrl = settings.getString(
								PreferencesActivity.KEY_SUBMISSION_URL,
								Collect.getInstance().getString(
										R.string.default_odk_submission));
						if (submissionUrl.charAt(0) != '/') {
							submissionUrl = "/" + submissionUrl;
						}

						urlString = urlString + submissionUrl;
					}

					// add the deviceID to the request...
					try {
						urlString += "?deviceID="
								+ URLEncoder.encode(deviceId, "UTF-8");
					} catch (UnsupportedEncodingException e) {
						// unreachable...
					}

					if (!uploadOneSubmission(urlString, id, instance, toUpdate,
							localContext, uriRemap, outcome)) {
						return outcome; // get credentials...
					}
				}
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}

		return outcome;
	}

	@Override
	protected void onPostExecute(Outcome outcome) {
		synchronized (this) {
			if (mStateListener != null) {
				if (outcome.mAuthRequestingServer != null) {
					mStateListener.authRequest(outcome.mAuthRequestingServer,
							outcome.mResults);
				} else {
					mStateListener.uploadingComplete(outcome.mResults);
				}
			}
		}
	}

	@Override
	protected void onProgressUpdate(Integer... values) {
		synchronized (this) {
			if (mStateListener != null) {
				// update progress and total
				mStateListener.progressUpdate(values[0].intValue(),
						values[1].intValue());
			}
		}
	}

	public void setUploaderListener(InstanceUploaderListener sl) {
		synchronized (this) {
			mStateListener = sl;
		}
	}
}
