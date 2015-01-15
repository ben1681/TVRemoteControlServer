package com.soniq.tvremotecontrolserver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.soniq.utils.MyUtils;
import com.soniq.utils.PackageUtils;

import android.content.Context;
import android.util.Log;

public class DownloadThread extends Thread {
	private Context _context;
	
	public final static String APK_DIRNAME	= "apks";

	
	public DownloadThread(Context context)
	{
		_context = context;
	}
	
	public void run() 
	{
		// check task
		
		while( !bExitFlag )
		{
			try{
				
				// ��ʽ����ʱ
				Thread.sleep(2000); //10000);
				
				while( !bExitFlag )
				{
					TaskInfo taskInfo = getDownloadTask();
					if( taskInfo == null )
					{
						MainData.showLog("no task!");

						break;
					}
					
					
					doDownloadTask(taskInfo);
					
					Thread.sleep(500);
				}
			}
			catch(Exception e)
			{
				
			}
		}
	}
	
	
	
	public final static String getApkDirName(Context context)
	{
			// �ڲ��洢
			
			String path = String.format("%s/%s", context.getFilesDir(), APK_DIRNAME);
			MyUtils.makeSureDirExists(path);
			

			// ����Ŀ¼Ȩ�ޣ�����װapkʱ����ʾ��������û��Ȩ�޷������Ŀ¼)
			// Ŀ¼Ҫ����Ȩ�ޣ�������ļ�Ҳһ��
        	MyUtils.execCmd("chmod 777 " + path);
			
			return path;
	}

	
	public int doDownloadTask(TaskInfo taskInfo)
	{
		MainData.showLog("do download task: " + taskInfo.appName + " url=" + taskInfo.downUrl);
		// ���ó���������״̬
		MainData.showLog("set status to downloading...");
		if( setDownloadTaskStatus(taskInfo.taskId, WAPI.TASK_STATUS_DOWNLOADING)  != 0 )
		{
			MainData.showLog("failed");
			return 1;
		}
		MainData.showLog("ok!");
		
		// ����
		
		// ���ر�����ļ���
		String filename = String.format("%s.apk", taskInfo.packageName);
		String fullname = String.format("%s/%s", getApkDirName(_context), filename);
		
		int ret = 1;
		int retry = 0;
		for(int i = 0; i < 3; i++ )
		{
			MainData.showLog("begin download..." + i);

			ret = do_download_file(taskInfo.downUrl, fullname);
			if( ret == 0 )
				break;
		}
		
		
		MainData.showLog("download result: " + ret);
		
		// ���ó��������(�ɹ�/ʧ��)״̬
		if( ret == 0 )
		{
			setDownloadTaskStatus(taskInfo.taskId, WAPI.TASK_STATUS_DOWNLOAD_SUCCESS);
			MainData.showLog("set status to download_cuccess");
			
			MainData.showLog("install " + taskInfo.packageName + " file:" + fullname);
			installApk(taskInfo.taskId, taskInfo.packageName, fullname);
		}
		else
		{
			setDownloadTaskStatus(taskInfo.taskId, WAPI.TASK_STATUS_DOWNLOAD_FAILED);
			MyUtils.deleteFile(fullname);
		}
		
		return 0;
	}
	
	public final static int THREAD_TASK_RETRY = 100;
	public final static int THREAD_TASK_INTERRUPTED = 200;
	
	public boolean bExitFlag = false;
	public int do_download_file(String remoteFile, String localFile)
	{
		InputStream input = null;
		
		FileOutputStream fos = null;
		
		try{
		
			File file = new File(localFile);
			long alreadyDownloadLength = 0;
			long totalLength = 0;
			long downloadLength = 0;
			
			if( file.exists() )
			{
				long len = file.length();
				alreadyDownloadLength = len;
			}
			
			URL url = new URL(remoteFile);
			HttpURLConnection httpConnection = (HttpURLConnection)url.openConnection();
			boolean bRange = false;
			if( alreadyDownloadLength > 0 )
			{
				bRange = true;
				String sRange = String.format("bytes=%d-", alreadyDownloadLength);
				httpConnection.setRequestProperty("RANGE", sRange);
			}
			
	//		httpConnection.setRequestProperty("User-Agent", MyProfile.HTTP_USERAGENT);
			
		//	httpConnection.setDoInput(true);
			httpConnection.setConnectTimeout(5000);
			httpConnection.setRequestMethod("GET");
			httpConnection.connect();
			int respCode = httpConnection.getResponseCode();
			if(  respCode == HttpURLConnection.HTTP_MOVED_TEMP )
			{
				// �ض�����	
	//			String newUrl = httpConnection.getHeaderField("Location");
	//			taskInfo.downloadUrl = newUrl;
	//
	//			return THREAD_TASK_REDIRECT;
				return 1; // ��ʱ��֧���ض����
			}
			
			
			if( bRange )
			{
				if( respCode == HttpURLConnection.HTTP_PARTIAL )
				{
					// ֧�ֶϵ�����
					String s_content_length = httpConnection.getHeaderField("Content-Length");
					String s_content_range = httpConnection.getHeaderField("Content-Range");
					if( !s_content_range.startsWith("bytes ") )
						return THREAD_TASK_RETRY;
					
					String s = s_content_range.substring(6);
					String[] ss = s.split("\\/");
					if( ss.length != 2 )
						return THREAD_TASK_RETRY;
					
					String s_total_length = ss[1];
					totalLength = Long.parseLong(s_total_length);
					downloadLength = Long.parseLong(s_content_length);
					
				}
				else if( respCode == HttpURLConnection.HTTP_OK || respCode == 416 )
				{
					// ��֧�ֶϵ�
					alreadyDownloadLength = 0;
					MyUtils.deleteFile(localFile);
				}
				else
				{
					// ����
					return THREAD_TASK_RETRY; // ����
				}
			}
			else
			{
				if( respCode != HttpURLConnection.HTTP_OK )
				{
					// ����
					return THREAD_TASK_RETRY;
				}
				
				//  ��ȡ�ܳ���
				String s_content_length = httpConnection.getHeaderField("Content-Length");
				totalLength = Long.parseLong(s_content_length);
				downloadLength = totalLength;
				
			}
			
			
			// OK, ��ʼ������
			
			input = httpConnection.getInputStream();
			
			file = new File(localFile);
			fos = new FileOutputStream(file, true);
			
			byte[] buf = new byte[1024];
			
			long nTotal = downloadLength;
	//		long t1 = MyUtils.getTickCount();
			
			while( nTotal > 0  )
			{
				if( bExitFlag )
				{
					input.close();
					return THREAD_TASK_INTERRUPTED;
				}
				
				int size = buf.length;
				if( size > nTotal )
					size = (int)nTotal;
				
				int n = input.read(buf, 0, size);
				if( n <= 0 ) //!= size )
				{
					input.close();
					return THREAD_TASK_RETRY;
				}
				// д�ļ�
				fos.write(buf, 0, n);
				fos.flush();
				
	
				nTotal -= n;
			}
			
			input.close();
			input = null;
			fos.close();
			fos = null;
			return 0;
		}
		catch(Exception e)
		{
			
		}
		finally
		{
			try{
			if( input != null )
				input.close();
			
			if( fos != null )
				fos.close();
			}
			catch(Exception e)
			{
				
			}
		}
		
		
		return 1;
	}
	
	public TaskInfo getDownloadTask()
	{
		try{
			String strUserId = MainData.get_profile_string_value(_context, MainData.PROFILE_BIND_USERID, "0");
			if( strUserId == null || strUserId.length() == 0 )
			{
				MainData.showLog("no user bind");
				return null;
			}
			
			int userId = Integer.parseInt(strUserId);
			if( userId <= 0 )
			{
				MainData.showLog("no user bind,ooops");
				return null;
			}
		
			String devid = MainData.getDeviceId(_context);
			String devname = MainData.get_profile_string_value(_context, MainData.PROFILE_SERVER_NAME, MainData.DEFAULT_SERVER_NAME);
			String urlString = WAPI.getGetDownloadTaskURLString(_context, userId, devid, devname);
			String response = WAPI.http_get_content(urlString);
			if( response == null || response.length() == 0 )
			{
				MainData.showLog("get download task failed");
				return null;
			}
			
			
			
			return WAPI.parseDownloadTaskInfo(response);
				
		}
		catch(Exception e)
		{
			
		}
		return null;
	}
	

	public int setDownloadTaskStatus(int taskId, int status)
	{
		try{
			String strUserId = MainData.get_profile_string_value(_context, MainData.PROFILE_BIND_USERID, "0");
			if( strUserId == null || strUserId.length() == 0 )
				return -1;
			
			int userId = Integer.parseInt(strUserId);
			if( userId <= 0 )
				return -2;
		
			String urlString = WAPI.getSetTaskStatusURLString(_context, userId, taskId, status);
			String response = WAPI.http_get_content(urlString);
			if( response == null || response.length() == 0 )
				return -3;
			
			
			
			return WAPI.parseGeneralResponse(response);
				
		}
		catch(Exception e)
		{
			
		}
		return -4;
	}
	
	

	private PackageUtils.InstallApkCallback _installApkCallback = new PackageUtils.InstallApkCallback() {
		
		@Override
		public void onInstallFinished(String packageName, int resultCode, int requestCode) {
			MainData.showLog("**********packageName:" + packageName + " resultCode:" + resultCode + " requestCode:" + requestCode);
			if( resultCode == 1 )
			{
				// install ok
				setDownloadTaskStatus(requestCode, WAPI.TASK_STATUS_INSTALL_SUCCESS);
				
				String filename = String.format("%s.apk", packageName);
				String fullname = String.format("%s/%s", getApkDirName(_context), filename);
				MyUtils.deleteFile(fullname);
			}
			else
			{
				setDownloadTaskStatus(requestCode, WAPI.TASK_STATUS_INSTALL_FAILED);

				// install failed
				String filename = String.format("%s.apk", packageName);
				String fullname = String.format("%s/%s", getApkDirName(_context), filename);
				MyUtils.deleteFile(fullname);
			}
		}

		@Override
		public void onUninstallFinished(String packageName, String appName,
				int resultCode) {
			// TODO Auto-generated method stub
		}
	};	
	
    public void installApk(int taskId, String packageName,String apkFilename)
    {
    	File file = new File(apkFilename);
    	if( file.exists() )
    	{		
    		MyUtils.execCmd("chmod 777 " + file.toString());
    	}
    	else
    		MainData.showLog("file " + apkFilename + " not exists");

    	int ret = PackageUtils.installApk(_context, apkFilename, taskId, _installApkCallback);
    	MainData.showLog("install apk ret=" + ret);
    }
}
