package com.example.hilde.smartcardreader;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Main Class
 */
public class MainActivity extends Activity {

    private EditText btName;
    private Button scanQR_Code;
    private bluetoothDevice btDevice;
    private boolean doubleBackToExitPressedOnce = false;
    private boolean debug = false;

    private static final String TAG = "SC-Terminal";
    private static final boolean D = true;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    private static final int REQUEST_ENABLE_BT = 2;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Array adapter for the conversation thread
    private ArrayAdapter<String> mConversationArrayAdapter;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private service_Bluetooth mChatService = null;
    private ListView mConversationView;

    Intent intent1;
    private CryptLib _crypt;

    private Boolean encryptData = false;
    private diffieHellman dh_helper;

    /**
     * Smartcard
     */
    public NfcAdapter nfcAdapter;
    public static IsoDep myNFCTag;
    public PendingIntent mPendingIntent;
    private String tagID;
    public String[][] mTechLists;
    public IntentFilter[] mFilters;

    /**
     * BroadcastReceiver for receiving message if smartcard is disconnected
     */
    private BroadcastReceiver smartcarddisconnect = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                String resultCode = bundle.getString("RESULT");
                if (resultCode.equals("TRUE")) {
                    systemLog("Smartcard disconnected.");
                    sendNewMessage(0,1, "smartcard_disconnected");
                    keepScreenOn(false);
                    stopSCservice();
                }
            }
        }
    };

    /**
     * Method for stopping service_Smartcard Thread
     */
    private void stopSCservice() {
        if(isMyServiceRunning(service_Smartcard.class.getName())){
            stopService(new Intent(this, service_Smartcard.class));
        }

    }

    /**
     * Method for set flag to keep screen awake
     * @param screen if true Display stays on
     */
    public void keepScreenOn(boolean screen) {
        if (screen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Toast.makeText(getApplicationContext(), "Screen will stay on.\nWhile Smartcard Connected.", Toast.LENGTH_LONG).show();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Toast.makeText(getApplicationContext(), "Screen will turn off.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * OnCreate Method load Interface
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btName = (EditText) findViewById(R.id.etStatus);
        scanQR_Code = (Button) findViewById(R.id.btScan);

        scanQR_Code.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scanQR_Code();
            }
        });

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<String>(this, R.layout.log_message);
        mConversationView = (ListView) findViewById(R.id.systemLog);
        mConversationView.setAdapter(mConversationArrayAdapter);
    }

    /**
     * Method for starting the Barcode-Scanner App
     */
    private void scanQR_Code(){
        if (scanQR_Code.getText().equals("Bluetooth Connect")) {
            IntentIntegrator scanIntegrator = new IntentIntegrator(this);
            scanIntegrator.initiateScan();
        } else {
            sendNewMessage(0,1, "application_stop");
            mChatService.stop();
            mConversationArrayAdapter.clear();
            systemLog("Connection Stopped.");
        }

    }

    /**
     * Method that receives Data from Barcode-Scanner App
     * @param requestCode
     * @param resultCode 0 if no Data read
     * @param intent Object with scanned Data
     */
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        IntentResult scanningResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);

        if (resultCode != 0) {
            String scanContent = scanningResult.getContents();
            //formatTxt.setText("FORMAT: " + scanFormat);

            String tmp[] = scanContent.split(">>");

            if (tmp.length == 5){
                btDevice = new bluetoothDevice(tmp[0], tmp[1], tmp[2], tmp[3], tmp[3]);
                dh_helper = new diffieHellman(btDevice.getdh_pub_p(), btDevice.getdh_pub_g(), btDevice.getdh_pub_B());
                if(D) Log.i(TAG, "Diffie Hellman Shared Secret: " + dh_helper.Get_shared_secret());
            } else{
                btDevice = new bluetoothDevice(tmp[0], "", "", "", tmp[1]);
            }

            // Get the BLuetoothDevice object
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(btDevice.getMACAddress());
            // Attempt to connect to the device
            systemLog(btDevice.getMACAddress());
            mChatService.connect(device);

            //if(D) Log.e(TAG, "Crypto AES>> " + btDevice.getBtAESKey());
            //if(D) Log.e(TAG, "Crypto Seed>> " + btDevice.getBtSeed());
            if(btDevice.getScDebug().equals("1")) {
                debug = true;
            } else {
                debug = false;
            }
        }
        else{
            Toast toast = Toast.makeText(getApplicationContext(), "No scan data received!", Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    /**
     * Method that creates IntentFilter for NFC-Module
     * registers Reciever for new Smartcards
     * enables ForegroudDispatch
     */
    private void startNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);

        try {
            ndef.addDataType("*/*");
            mFilters = new IntentFilter[] { ndef, };
            mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("fail", e);
        }

        if ( nfcAdapter != null ) {
            nfcAdapter.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }

        registerReceiver(smartcarddisconnect, new IntentFilter(service_Smartcard.NOTIFICATION_CLOSE));
    }

    /**
     * Method called when Intent occurrs from NFC Interface
     * calls resolveIntent Method
     * @param intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        resolveIntent(intent);
    }

    /**
     * Method which is called when new Smartcard is detected
     * @param intent
     */
    private void resolveIntent(Intent intent) {
        String action = intent.getAction();

        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Parcelable tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            final Tag t = (Tag) tag;
            myNFCTag = IsoDep.get(t);

            systemLog("Smartcard discovered.");
            sendNewMessage(0,1, "smartcard_discovered");

            if( !myNFCTag.isConnected() ) {
                try {
                    myNFCTag.connect();
                    myNFCTag.setTimeout(5000);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            if( myNFCTag.isConnected() ) {
                Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                vib.vibrate(500);

                tagID = byteToHexString(t.getId());

                systemLog("Smartcard connected.");
                sendNewMessage(0,1, "smartcard_connected");

                keepScreenOn(true);


                //if(!isMyServiceRunning(service_Smartcard.class.getName())){
                    intent1 = new Intent(this, service_Smartcard.class);
                    startService(intent1);
                //}
            } else {
                systemLog("Smartcard connection refused.");
                sendNewMessage(0,1, "smartcard_connection_refused");
            }
        }
    }

    /**
     * Convert Byte Array to Hex String with Spaces between Bytes
     * @param dataToConvert Byte Array
     * @return Hey String
     */
    public static String byteToHexString(byte[] dataToConvert) {
        StringBuilder sb = new StringBuilder();
        for ( byte b : dataToConvert ) {
            sb.append( String.format("%02X ", b) );
        }
        return sb.toString();
    }

    /**
     * COnverts String Data to Byte-Array
     * @param data String Data
     * @return byte-Array with Hex-Data
     */
    public static byte[] hexToByteArray(String data) {

        String hexchars = "0123456789abcdef";
        data = data.replaceAll(" ","").toLowerCase();

        if ( data == null ) {
            return null;
        }

        byte[] hex = new byte[data.length() / 2];

        for ( int ii = 0; ii < data.length(); ii += 2 ) {
            int i1 = hexchars.indexOf(data.charAt(ii));
            int i2 = hexchars.indexOf(data.charAt(ii + 1));
            hex[ii/2] = (byte)((i1 << 4) | i2);
        }
        return hex;
    }

    /**
     * Converts Byte-Array to String
     * @param dataToConvert
     * @return
     */
    public static String byteToString(byte[] dataToConvert) {
        StringBuilder sb = new StringBuilder();
        for ( byte b : dataToConvert ) {
            sb.append( String.format("%02X", b) );
        }
        return sb.toString();
    }

    /**
     * Send APDU to Smartcard and receive response
     * @param dataToSend Data which is send to smartcard
     * @return response from smartcard
     */
    public static byte[] sendandReciveData(byte[] dataToSend) {
        byte[] resp = null;

        try {
            if(D) Log.e(TAG, "SC Send >> " + byteToHexString(dataToSend));
            resp = myNFCTag.transceive(dataToSend);
        } catch (IOException e) {
            if(D) Log.e(TAG, "SC >> No Card Response.");
        }

        if(D) Log.e(TAG, "SC Receive >> " + byteToHexString(resp));

        return resp;
    }

    /**
     * Method that checks if Service is running
     * @param className Name of Class which should bechecked
     * @return true if Service is running
     */
    private boolean isMyServiceRunning(String className) {
        ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (className.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create Bluetooth Object
     */
    private void setupBT(){
        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new service_Bluetooth(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    /**
     * Enable on start Bluetooth if not already enabled
     * Create NFCManager object
     */
    @Override
    protected void onStart() {
        super.onStart();
        if(D) Log.e(TAG, "--- ON Start ---");

        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else {
            if (mChatService == null) {
                setupBT();
            }
        }

        NfcManager manager = (NfcManager) getSystemService(Context.NFC_SERVICE);
        NfcAdapter adapter = manager.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            // adapter exists and is enabled.
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Smartcard Reader");
            builder.setMessage("Please enable NFC!");

            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    connectionStoped();
                    finish();
                }
            });

            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    connectionStoped();
                    finish();
                }
            });

            builder.show();
        }
    }

    /**
     * Start ChatService when Display is on
     * if Bluetooth connected start nfc
     */
    @Override
    protected synchronized void onResume() {
        super.onResume();
        if(D) Log.e(TAG, "--- ON Resume ---");

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == service_Bluetooth.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            } else if (mChatService.getState() == service_Bluetooth.STATE_CONNECTED){
                startNFC();
            }
        }
    }

    /**
     * Stop every service when closing application
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(D) Log.e(TAG, "--- ON DESTROY ---");

        if (mChatService != null) {
            sendNewMessage(0, 1, "application_stop");
            mChatService.stop();
        }

        if ( nfcAdapter != null ) {
            nfcAdapter.disableForegroundDispatch(this);
        }

        stopSCservice();
        connectionStoped();
    }

    /**
     * Method for create new systemlod entry
     * @param msg
     */
    private void systemLog(String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String currentDateandTime = sdf.format(new Date());

        //mConversationArrayAdapter.add(currentDateandTime + ":\n" + msg + "\n" + "-------------------------");
        mConversationArrayAdapter.insert(currentDateandTime + " >> " + msg, 0);
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     * This Method receives state changes from Bluetooth module
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case service_Bluetooth.STATE_CONNECTED:
                            try {
                                _crypt = new CryptLib();
                            } catch (Exception e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                            if (btDevice.getdh_pub_p().length() > 0){
                                sendNewMessage(0, 0, "connected>>" + dh_helper.Get_public_A());
                            } else{
                                sendNewMessage(0, 0, "connected");
                            }

                            try{
                                btDevice.set_AES_KEY(_crypt.SHA256(dh_helper.Get_shared_secret(), 32));
                                btDevice.set_AES_IV(_crypt.SHA256(dh_helper.Get_shared_secret(), 16));
                                encryptData = true;
                            } catch (Exception e){}
                            break;
                        case service_Bluetooth.STATE_CONNECTING:
                            //btName.setText("Connecting...");
                            systemLog("Connecting");
                            scanQR_Code.setClickable(false);
                            break;
                        case service_Bluetooth.STATE_LISTEN:
                        case service_Bluetooth.STATE_NONE:
                            //btName.setText("Not Connected.");
                            systemLog("No Connection");
                            scanQR_Code.setText("Bluetooth Connect");
                            scanQR_Code.setClickable(true);
                            btName.setText("Not Connected");
                            btDevice = null;
                            connectionStoped();
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    writeMessage = writeMessage.substring(0, writeMessage.length());
                    //systemLog("Message Write: " + writeMessage);
                    if (D) Log.d(TAG, "BT Sender Message:" + writeMessage);
                    break;
                case MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    readMessage = readMessage.substring(0, readMessage.length());
                    if (D) Log.d(TAG, "BT Receiver Message:" + readMessage);
                    receiveMessage(readMessage);
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Toast.makeText(getApplicationContext(), "Connected to "
                            + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    /**
     * Sends a Bluetooth message.
     * @param message  A string of text to send.
     */
    public void sendNewMessage(int id, int code, String message) {
        // Check that we're actually connected before trying anything
        if (mChatService.getState() != service_Bluetooth.STATE_CONNECTED) {
            Toast.makeText(this, "Not Connected.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            if (D) Log.d(TAG, "Send Message:" + message);
            if (encryptData){
                try {
                    message = _crypt.encrypt(message, btDevice.get_AES_KEY(), btDevice.get_AES_IV()); //encrypt
                    message = message.replaceAll("\n", "");
                    if (D) Log.d(TAG, "Encrypted Message:" + message);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }

            message = id + ">>" + code + ">>" + message + "\n";
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mChatService.write(send);
        }
    }

    /**
     * Method is called when new message is received
     * Splits message and sends data to checkMessage
     * @param message
     */
    private void receiveMessage(String message) {
        String msg[] = message.split(">>");
        checkMessage(Integer.valueOf(msg[0]), Integer.valueOf(msg[1]), msg[2]);
    }

    /**
     * Method that checks received messages
     * @param id ID of message
     * @param code Code of Message
     * @param msg Message
     */
    private void checkMessage(int id, int code, String msg) {
        if (encryptData){
            try {
                msg = _crypt.decrypt(msg, btDevice.get_AES_KEY(),btDevice.get_AES_IV()); //decrypt
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else{
            msg = msg.replaceAll("\n", "");
        }

        if (D) Log.d(TAG, "Received Message:" + msg );
        switch (code) {
            /** System Message*/
            case 1:
                if (msg.startsWith("connected")){
                    systemLog("Successfully Connected");
                    btName.setText(mConnectedDeviceName);
                    scanQR_Code.setText("Bluetooth Disconnect");
                    scanQR_Code.setClickable(true);
                    startNFC();
                } else if (msg.startsWith("refused")){
                    systemLog("Connection refused.");
                    scanQR_Code.setText("Bluetooth Connect");
                    scanQR_Code.setClickable(true);
                    btName.setText("Not Connected");
                    keepScreenOn(false);
                } else if (msg.startsWith("application_stop")){
                    mChatService.stop();
                    mConversationArrayAdapter.clear();
                    systemLog("Connection Stopped.");
                } else {
                    systemLog("Error establish Connection.");
                }
                break;
            /** Smartcard Message*/
            case 2:
                if (myNFCTag.isConnected()){
                    byte[] resp = sendandReciveData(hexToByteArray(msg));
                    if(debug) {
                        systemLog("SC >> " + msg);
                        systemLog("SC << " + byteToHexString(resp));
                    }

                    sendNewMessage(id, 2, byteToString(resp));
                } else{
                    sendNewMessage(id, 3, "scIsDisconnected");
                }
                break;
            case 3:
                if( myNFCTag.isConnected() ) {
                    sendNewMessage(id, 3, "scIsConnected");
                } else{
                    sendNewMessage(id, 3, "scIsDisconnected");
                }
                break;
            case 4:
                sendNewMessage(id, 4, msg);
                break;
            case 5:
                sendNewMessage(id, 4, "connected");
                break;
            /** Catch if there is an Error*/
            default:
                systemLog("Connection Error.");
                break;
        }
    }

    /**
     * Stop Bluetooth connection
     */
    private void connectionStoped() {
        if(smartcarddisconnect != null) {
            try {
                unregisterReceiver(smartcarddisconnect);
            } catch (Exception e) {
                if (D) Log.d(TAG, "connectionStoped() >>" + e);
            }

        }

        if ( nfcAdapter != null ) {
            nfcAdapter.disableForegroundDispatch(this);
            nfcAdapter = null;
        }
        encryptData = false;
        keepScreenOn(false);
    }

    /**
     * Handle Back pressed event
     * Close App if pressed 2 times
     */
    @Override
    public void onBackPressed() {
        //Überprüfe ob Methode schonmal aufgerufen wurde, wenn ja dann schließe die Activity
        if (doubleBackToExitPressedOnce) {
            //App wird geschlossen
            super.onBackPressed();

            if ( nfcAdapter != null ) {
                nfcAdapter.disableForegroundDispatch(this);
                nfcAdapter = null;
            }

            if ( nfcAdapter != null ) {
                nfcAdapter.disableForegroundDispatch(this);
            }

            sendNewMessage(0, 1, "application_stop");
            mChatService.stop();
            keepScreenOn(false);
            return;
        }

        //Setze Feld auf true und zeige Toast an, dass man die Taste nochmal drücken muss um die Activity zu schließen
        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();

        //Starte Timer, der nach 2 Sekunden das Feld wieder auf false setzt
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                doubleBackToExitPressedOnce = false;
            }
        }, 2000);
    }
}
