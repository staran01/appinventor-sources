package com.google.appinventor.client.explorer.commands;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;

import org.omg.CORBA.portable.InputStream;

//import com.google.appengine.repackaged.org.apache.commons.el.Logger;
import com.google.appinventor.client.output.OdeLog;
import com.google.appinventor.client.utils.Downloader;
import com.google.appinventor.shared.rpc.ServerLayout;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.common.base.Preconditions;
import com.google.gwt.core.client.GWT;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.logging.impl.LoggerImplRegular;
import com.google.gwt.user.client.Window;

public class SmartLabCommand extends ChainableCommand {
	private String target;
	private String username;
	private String key;

	public SmartLabCommand(String target, String username, String key) {
		super(null); // no next command
		Preconditions.checkNotNull(target);
		this.target = target;
		this.username = username;
		this.key = key;
	}

	@Override
	protected boolean willCallExecuteNextCommand() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	protected void execute(ProjectNode node) {

		// Window.alert("Username: "+username+" Key: "+key);

		// Window.alert(GWT.getModuleBaseURL()+ServerLayout.DOWNLOAD_SERVLET_BASE
		// + ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/"
		// + node.getProjectId() + "/" + target);

		/*
		 * RequestBuilder rb = new RequestBuilder(RequestBuilder.POST,
		 * "http://localhost:9098/json/json/jsontest");
		 * rb.setHeader("Content-Type", "application/x-www-form-urlencoded");
		 * try { rb.sendRequest("jsontext="+hellothere.toString(), new
		 * DateCallbackHandler()); } catch (RequestException e) {
		 * Window.alert(e.toString()); }
		 */

		String uri = GWT.getModuleBaseURL()
				+ ServerLayout.DOWNLOAD_SERVLET_BASE
				+ ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/"
				+ node.getProjectId() + "/" + target;

		RequestBuilder rb = new RequestBuilder(RequestBuilder.GET,
				"http://smartlab-ds1.cs.ucy.ac.cy/app_inventor/push.php/?"
						+ "username=" + username + "&api_key=" + key + "&link="
						+ uri);
		
	//	RequestBuilder rb = new RequestBuilder(RequestBuilder.GET,"localhost:8888/ode/download/project-output/5629499534213120/Android");

		try {
			rb.sendRequest(null, new RequestCallback() {

				@Override
				public void onResponseReceived(Request arg0, Response arg1) {
					// TODO Auto-generated method stub
					
				}

				@Override
				public void onError(Request arg0, Throwable arg1) {
					// TODO Auto-generated method stub

				}
			});
		} catch (RequestException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Downloader.getInstance().download(ServerLayout.DOWNLOAD_SERVLET_BASE
		// +
		// ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/" + node.getProjectId() +
		// "/" + target);
		// LoggerImplRegular l =new LoggerImplRegular();
		// l.log(Level.ALL,"YOYOOOOYOYOYOYOYOYOYO");
		//
		// OdeLog.log("====================-------------------------------======================");
		// OdeLog.log(ServerLayout.DOWNLOAD_SERVLET_BASE +
		// ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/" + node.getProjectId() +
		// "/" + target);
		/*
		 * String url = ServerLayout.DOWNLOAD_SERVLET_BASE +
		 * ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/" + node.getProjectId() +
		 * "/" + target; String upload = "http://yo-yo.com"; try {
		 * 
		 * URL downloadURL = new URL(url); URL uploadURL = new URL(upload);
		 * HttpURLConnection connection = null; HttpURLConnection connectionS =
		 * null; connection = (HttpURLConnection) downloadURL.openConnection();
		 * connection.setDoOutput(true); connection.setRequestMethod("GET");
		 * InputStream in = (InputStream) connection.getInputStream();
		 * 
		 * ByteArrayOutputStream ous = new ByteArrayOutputStream(); byte []
		 * buffer = new byte[16384]; int read; while((read =
		 * in.read(buffer,0,buffer.length)) != -1){ ous.write(buffer); }
		 * ous.flush(); connectionS = (HttpURLConnection)
		 * uploadURL.openConnection(); connectionS.setRequestMethod("POST");
		 * connectionS.setDoOutput(true);
		 * connectionS.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
		 * DataOutputStream out = new
		 * DataOutputStream(connectionS.getOutputStream());
		 * out.write(ous.toByteArray()); out.flush(); out.close();
		 * 
		 * } catch (IOException e) { // TODO Auto-generated catch block
		 * e.printStackTrace(); }
		 */

	}
}
