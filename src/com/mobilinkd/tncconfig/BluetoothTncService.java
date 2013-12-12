/*
 * Copyright (C) 2013 Mobilinkd LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mobilinkd.tncconfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

// import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class sets up and manages the Bluetooth connection with the TNC.
 * It has a thread for connecting with the device and a thread for
 * performing data transmissions when connected.  The structure of this
 * code is based on BluetoothChat from the Android SDK examples.
 */
public class BluetoothTncService {
    // Debugging
    private static final String TAG = "BluetoothTncService";
    private static final boolean D = true;

    // UUID for this serial port protocol
    private static final UUID SPP_UUID = 
    		UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    // private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device

    public static final int SEND_SPACE = 1;
    public static final int SEND_MARK = 2;
    public static final int SEND_BOTH = 3;
    
    private static final byte[] TNC_SET_TX_DELAY = 
    		new byte[] { (byte)0xc0, 0x01, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_PERSISTENCE = 
    		new byte[] { (byte)0xc0, 0x02, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_SLOT_TIME = 
    		new byte[] { (byte)0xc0, 0x03, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_TX_TAIL = 
    		new byte[] { (byte)0xc0, 0x04, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_DUPLEX = 
    		new byte[] { (byte)0xc0, 0x05, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_BT_CONN_TRACK = 
    		new byte[] { (byte)0xc0, 0x06, 0x45, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_VERBOSITY = 
    		new byte[] { (byte)0xc0, 0x06, 0x10, 0, (byte)0xC0 };
    private static final byte[] TNC_SET_INPUT_ATTEN = 
    		new byte[] { (byte)0xc0, 0x06, 0x02, 0, (byte)0xC0 };
    private static final byte[] TNC_STREAM_VOLUME =
    		new byte[] { (byte)0xc0, 0x06, 0x05, (byte)0xC0 };
    private static final byte[] TNC_PTT_MARK = 
    		new byte[] { (byte)0xc0, 0x06, 0x07, (byte)0xC0 }; 
    private static final byte[] TNC_PTT_SPACE =
    		new byte[] { (byte)0xc0, 0x06, 0x08, (byte)0xC0 };
    private static final byte[] TNC_PTT_BOTH =
    		new byte[] { (byte)0xc0, 0x06, 0x09, (byte)0xC0 };
    private static final byte[] TNC_PTT_OFF = 
    		new byte[] { (byte)0xc0, 0x06, 0x0A, (byte)0xC0 };
    private static final byte[] TNC_SET_OUTPUT_VOLUME = 
    		new byte[] { (byte)0xc0, 0x06, 0x01, 0, (byte)0xC0 };
    private static final byte[] TNC_GET_OUTPUT_VOLUME =
    		new byte[] { (byte)0xc0, 0x06, 0x0C, (byte)0xC0 };
    private static final byte[] TNC_SET_SQUELCH_LEVEL = 
    		new byte[] { (byte)0xc0, 0x06, 0x03, 0, (byte)0xC0 };
    private static final byte[] TNC_GET_ALL_VALUES = 
    		new byte[] { (byte)0xc0, 0x06, 0x7F, (byte)0xC0 };

    /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     */
    public BluetoothTncService(Context context, Handler handler) {
        // mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(TncConfig.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     * Return the current connection state. */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the TNC service. Called by the Activity onResume() */
    public synchronized void start() {
        if (D) Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        setState(STATE_NONE);
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     */
    public synchronized void connect(BluetoothDevice device) {
        if (D) Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (D) Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(TncConfig.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(TncConfig.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);
        listen();
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (D) Log.d(TAG, "stop");
        if (mState == STATE_CONNECTED) {
        	mConnectedThread.write(TNC_PTT_OFF);
        }
        
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
        setState(STATE_NONE);
    }
    
    public void getAllValues()
    {
        if (D) Log.d(TAG, "getAllValues()");
        
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        r.write(TNC_GET_ALL_VALUES);
    }

    public void getOutputVolume()
    {
        if (D) Log.d(TAG, "getOutputVolume()");
        
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        r.write(TNC_GET_OUTPUT_VOLUME);
    }

    public void ptt(int mode)
    {
        if (D) Log.d(TAG, "ptt() " + mode);
        
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }

        switch(mode)
        {
        case SEND_MARK:
        	r.write(TNC_PTT_MARK);
        	break;
        case SEND_SPACE:
        	r.write(TNC_PTT_SPACE);
        	break;
        case SEND_BOTH:
        	r.write(TNC_PTT_BOTH);
        	break;
        default:
        	r.write(TNC_PTT_OFF);
        	break;
        }
    }
    
    public void setDcd(boolean on)
    {
        if (D) Log.d(TAG, "setDcd()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_SQUELCH_LEVEL;
        if (!on)
        {
        	c[3] = 2;
        }
        else
        {
        	c[3] = 0;
        }
    	r.write(c);
    }
    
    public void setTxDelay(int value)
    {
        if (D) Log.d(TAG, "setTxDelay()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_TX_DELAY;
        c[2] = (byte) value;
    	r.write(c);
    }
    
    public void setPersistence(int value)
    {
        if (D) Log.d(TAG, "setPersistence()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_PERSISTENCE;
        c[2] = (byte) value;
    	r.write(c);
    }
    
    public void setSlotTime(int value)
    {
        if (D) Log.d(TAG, "setSlotTime()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_SLOT_TIME;
        c[2] = (byte) value;
    	r.write(c);
    }
    
    public void setTxTail(int value)
    {
        if (D) Log.d(TAG, "setTxTail()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_TX_TAIL;
        c[2] = (byte) value;
    	r.write(c);
    }
    
    public void setDuplex(boolean on)
    {
        if (D) Log.d(TAG, "setDuplex()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_DUPLEX;
        if (on)
        {
        	c[2] = 1;
        }
        else
        {
        	c[2] = 0;
        }
    	r.write(c);
    }
    
    public void setConnTrack(boolean on)
    {
        if (D) Log.d(TAG, "setConnTrack()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_BT_CONN_TRACK;
        if (on)
        {
        	c[3] = 1;
        }
        else
        {
        	c[3] = 0;
        }
    	r.write(c);
    }
    
    public void setVerbosity(boolean on)
    {
        if (D) Log.d(TAG, "setVerbosity(" + on + ")");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_VERBOSITY;
        if (on)
        {
        	c[3] = 1;
        }
        else
        {
        	c[3] = 0;
        }
    	r.write(c);
    }
    
    public void setInputAtten(boolean on)
    {
        if (D) Log.d(TAG, "setInputAtten()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_INPUT_ATTEN;
        if (on)
        {
        	c[3] = 2;
        }
        else
        {
        	c[3] = 0;
        }
    	r.write(c);
    }
    
    public void listen()
    {
        if (D) Log.d(TAG, "listen()");
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
    	r.write(TNC_PTT_OFF);
    	r.write(TNC_STREAM_VOLUME);
    }
    
    public void volume(int v)
    {
        if (D) Log.d(TAG, "volume() = " + v);
    	
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        
        byte c[] = TNC_SET_OUTPUT_VOLUME;
        c[3] = (byte) v;
    	r.write(c);
    }
    
    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        setState(STATE_NONE);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(TncConfig.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TncConfig.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        setState(STATE_NONE);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(TncConfig.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TncConfig.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }
    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "create() failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed();
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                // Start the service over to restart listening mode
                BluetoothTncService.this.start();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothTncService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
    
    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        private class HdlcDecoder {
        	
        	static final int BUFFER_SIZE = 330;
        	
        	static final byte FEND = (byte) 192;
        	static final byte FESC = (byte) 219;
        	static final byte TFEND = (byte) 220;
        	static final byte TFESC = (byte) 221;
        	
        	static final int STATE_WAIT_FEND = 0;
        	static final int STATE_WAIT_HW = 1;
        	static final int STATE_WAIT_ESC = 2;
        	static final int STATE_WAIT_DATA = 3;
        	
        	public static final int TNC_INPUT_VOLUME = 4;
        	public static final int TNC_OUTPUT_VOLUME = 12;
        	public static final int TNC_GET_TXDELAY = 33;
        	public static final int TNC_GET_PERSIST = 34;
        	public static final int TNC_GET_SLOTTIME = 35;
        	public static final int TNC_GET_TXTAIL = 36;
        	public static final int TNC_GET_DUPLEX = 37;
        	public static final int TNC_GET_SQUELCH_LEVEL = 14;
        	public static final int TNC_GET_HW_VERSION = 41;
        	public static final int TNC_GET_FW_VERSION = 40;
        	public static final int TNC_GET_VERBOSITY = 17;
        	public static final int TNC_GET_BATTERY_LEVEL = 6;
        	public static final int TNC_GET_INPUT_ATTEN = 13;
        	public static final int TNC_GET_BT_CONN_TRACK = 70;
        	
        	boolean mAvailable = false;
        	byte[] mBuffer = new byte[BUFFER_SIZE];
        	int mPos = 0;
        	int mState = STATE_WAIT_FEND;
        	
        	public HdlcDecoder() {
        		
        	}
        	
        	public boolean available() {return mAvailable;}
        	
        	public void process(byte c)
        	{
        		switch (mState) {
        		case STATE_WAIT_FEND:
        			if (c == FEND) {
        				mPos = 0;
        				mAvailable = false;
        				mState = STATE_WAIT_HW;
        			}
        			break;
        		case STATE_WAIT_HW:
        			if (c == FEND) break;
        			if (c == (byte) 0x06) {
        				mState = STATE_WAIT_DATA;
        			}
        			else {
        				Log.e(TAG, "Invalid packet type received " + (int)c);
        				mState = STATE_WAIT_FEND;
        			}
        			break;
        		case STATE_WAIT_ESC:
        			switch (c) {
        			case TFESC:
        				mBuffer[mPos++] = FESC;
        				break;
        			case TFEND:
        				mBuffer[mPos++] = FEND;
        				break;
        			default:
            			mBuffer[mPos++] = c;
            			break;
        			}
        			mState = STATE_WAIT_DATA;
        			break;
        		case STATE_WAIT_DATA:
        			switch (c) {
        			case FESC:
        				mState = STATE_WAIT_ESC;
        				break;
        			case FEND:
        				if (mPos > 1) mAvailable = true;
        				mState = STATE_WAIT_FEND;
        			default:
        				mBuffer[mPos++] = c;
        				break;
        			}
        		}
        	}
        	
        	int getType() {return (int) mBuffer[0] & 0xff;}
        	int getValue() {return (int) mBuffer[1] & 0xff;}
        	int size() {return mPos - 1;}
        	byte[] data()
        	{
        		byte[] result = new byte[mPos - 2];
        		System.arraycopy(mBuffer, 1, result, 0, mPos - 2);
        		return result;
        	}
        }
        
        private final HdlcDecoder mHdlc;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            mHdlc = new HdlcDecoder();

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    byte c = (byte) mmInStream.read();
                    mHdlc.process(c);
                    if (mHdlc.available()) {
                    	switch (mHdlc.getType()) {
                    	case HdlcDecoder.TNC_INPUT_VOLUME:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_INPUT_VOLUME, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_OUTPUT_VOLUME:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_OUTPUT_VOLUME, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_TXDELAY:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_TX_DELAY, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_PERSIST:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_PERSISTENCE, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_SLOTTIME:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_SLOT_TIME, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_TXTAIL:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_TX_TAIL, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_DUPLEX:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_DUPLEX, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_SQUELCH_LEVEL:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_SQUELCH_LEVEL, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_HW_VERSION:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_HW_VERSION, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_FW_VERSION:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_HW_VERSION, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_BATTERY_LEVEL:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_BATTERY_LEVEL, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_BT_CONN_TRACK:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_BT_CONN_TRACK, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_VERBOSITY:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_VERBOSITY, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	case HdlcDecoder.TNC_GET_INPUT_ATTEN:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_INPUT_ATTEN, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	default:
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(
                            		TncConfig.MESSAGE_OTHER, (int) mHdlc.getValue(),
                            		mHdlc.size(), mHdlc.data()).sendToTarget();
                            break;
                    	}
                    }

                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
