package com.shootoff.camera.cameratypes;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamException;
import com.github.sarxos.webcam.ds.ipcam.IpCamAuth;
import com.github.sarxos.webcam.ds.ipcam.IpCamDevice;
import com.github.sarxos.webcam.ds.ipcam.IpCamDeviceRegistry;
import com.github.sarxos.webcam.ds.ipcam.IpCamMode;
import com.shootoff.camera.CameraFactory;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.config.Configuration;

public class IpCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(IpCamera.class);
	private final Webcam ipcam;
	
	private boolean closing = false;
	
	public IpCamera(final Webcam ipcam) {
		this.ipcam = ipcam;
	}
	
	protected Webcam getWebcam() {
		return this.ipcam;
	}
	
	public static IpCamera registerIpCamera(String cameraName, URL cameraURL, Optional<String> username,
			Optional<String> password)
			throws MalformedURLException, URISyntaxException, UnknownHostException, TimeoutException {
		// These are here because webcam-capture wraps this exception in a
		// WebcamException if the
		// URL has a syntax issue. We don't want to use webcam-capture classes
		// outside of this
		// class, thus to handle this error we need to artificially cause it
		// earlier if it is
		// going to be a problem.
		cameraURL.toURI();

		try {
			IpCamDevice ipcam;
			if (username.isPresent() && password.isPresent()) {
				IpCamAuth auth = new IpCamAuth(username.get(), password.get());
				ipcam = IpCamDeviceRegistry.register(new IpCamDevice(cameraName, cameraURL, IpCamMode.PUSH, auth));
			} else {
				ipcam = IpCamDeviceRegistry.register(new IpCamDevice(cameraName, cameraURL, IpCamMode.PUSH));
			}

			// If a camera can't be reached, webcam capture seems to freeze
			// indefinitely. This is done
			// to add an artificial timeout.
			Thread t = new Thread(() -> ipcam.getResolution(), "GetIPcamResolution");
			t.start();
			final int ipcamTimeout = 6000;
			try {
				t.join(ipcamTimeout);
			} catch (InterruptedException e) {
				logger.error("Error connecting to webcam", e);
			}

			if (t.isAlive()) {
				IpCamDeviceRegistry.unregister(cameraName);
				throw new TimeoutException();
			}

			return new IpCamera(Webcam.getWebcamByName(cameraName));
		} catch (WebcamException we) {
			Throwable cause = we.getCause();

			if (cause instanceof UnknownHostException) {
				throw (UnknownHostException) cause;
			}

			logger.error("Error connecting to webcam", we);
			throw we;
		}
	}

	public static boolean unregisterIpCamera(final String cameraName) {
		return IpCamDeviceRegistry.unregister(cameraName);
	}

	@Override
	public Mat getMatFrame() {
		
		return Camera.bufferedImageToMat(getBufferedImage());
	}

	@Override
	public BufferedImage getBufferedImage() {
		currentFrameTimestamp = System.currentTimeMillis();
		frameCount++;
		return ipcam.getImage();
	}

	@Override
	public boolean open() {
		boolean open = false;
		try {
			open = ipcam.open();
		} catch (WebcamException we) {
			open = false;
		}
		if (open) CameraFactory.openCamerasAdd(this);
		return open;
	}

	@Override
	public boolean isOpen() {
		return ipcam.isOpen();
	}

	@Override
	public boolean close() {
		if (CameraFactory.isMac()) {
			new Thread(() -> {
				ipcam.close();
			}, "CloseMacOSXWebcam").start();
			
			CameraFactory.openCamerasRemove(this);
			closing = true;
			return true;
		} else {
			boolean res = ipcam.close();
			if (res) CameraFactory.openCamerasRemove(this);
			closing = true;
			return res;
		}
	}

	@Override
	public String getName() {
		return ipcam.getName();
	}

	@Override
	public boolean isLocked() {
		return ipcam.getLock().isLocked();
	}

	private boolean isImageNew() {
		return ipcam.isImageNew();
	}

	@Override
	public void setViewSize(Dimension size) {
		try {
			ipcam.setCustomViewSizes(new Dimension[] { size });

			ipcam.setViewSize(size);
		} catch (IllegalArgumentException e) {
			logger.error(String.format("Failed to set dimensions for camera: camera.getName() = %s", getName()), e);
		}
	}

	@Override
	public Dimension getViewSize() {
		return ipcam.getViewSize();
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((ipcam == null) ? 0 : ipcam.hashCode());
		return result;
	}

	@Override
	public ShotDetector getPreferredShotDetector(final CameraManager cameraManager, final Configuration config, final CameraView cameraView)
	{
		if (JavaShotDetector.isSystemSupported())
			return new JavaShotDetector(cameraManager, config, cameraView);
		else
			return null;
	}
	
	
	@Override
	public void run() {
		while (isOpen() && !closing)
		{
			if (!isImageNew()) continue;
			
			if (cameraEventListener.isPresent())
				cameraEventListener.get().newFrame(getMatFrame());
			
			if (((int) (getFrameCount() % Math.min(getFPS(), 5)) == 0) && cameraState != CameraState.CALIBRATING) {
				estimateCameraFPS();
			}
			
		}
		if (cameraEventListener.isPresent())
			cameraEventListener.get().cameraClosed();
	}
}
