package com.google.appinventor.client.explorer.commands;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import org.omg.CORBA.portable.InputStream;

import com.google.appinventor.shared.rpc.ServerLayout;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.common.base.Preconditions;

public class SmartLabCommand extends ChainableCommand{
	private String target;
	
	public SmartLabCommand(String target){
		super(null); // no next command
	    Preconditions.checkNotNull(target);
	    this.target = target;
	}

	@Override
	protected boolean willCallExecuteNextCommand() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	protected void execute(ProjectNode node) {

		/*String url = ServerLayout.DOWNLOAD_SERVLET_BASE + ServerLayout.DOWNLOAD_PROJECT_OUTPUT + "/" + node.getProjectId() + "/" + target;
		String upload = "http://yo-yo.com";
			try {
				
				URL downloadURL = new URL(url);
				URL uploadURL = new URL(upload);
				HttpURLConnection connection = null;
				HttpURLConnection connectionS = null;
				connection = (HttpURLConnection) downloadURL.openConnection();
				connection.setDoOutput(true);
				connection.setRequestMethod("GET");
				InputStream in = (InputStream) connection.getInputStream();
	
				ByteArrayOutputStream ous = new ByteArrayOutputStream();
				byte [] buffer = new byte[16384];
				int read;
				while((read = in.read(buffer,0,buffer.length)) != -1){
					ous.write(buffer);
				}
				ous.flush();
				connectionS = (HttpURLConnection) uploadURL.openConnection();
				connectionS.setRequestMethod("POST");
				connectionS.setDoOutput(true);
				connectionS.setRequestProperty("Accept-Language", "en-US,en;q=0.5");				
				DataOutputStream out = new DataOutputStream(connectionS.getOutputStream());
				out.write(ous.toByteArray());
				out.flush();
				out.close();
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}*/
		      
	}

}
