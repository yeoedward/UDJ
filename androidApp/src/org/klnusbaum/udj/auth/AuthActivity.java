/**
 * Copyright 2011 Kurtis L. Nusbaum
 *
 * This file is part of UDJ.
 *
 * UDJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * UDJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with UDJ.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.klnusbaum.udj.auth;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.os.Bundle;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.Dialog;
import android.util.Log;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.widget.TextView;
import android.view.Window;
import android.text.Html;
import android.text.method.LinkMovementMethod;

import org.apache.http.auth.AuthenticationException;

import java.io.IOException;

import org.klnusbaum.udj.R;
import org.klnusbaum.udj.Utils;
import org.klnusbaum.udj.Constants;
import org.klnusbaum.udj.network.ServerConnection;
import org.klnusbaum.udj.exceptions.APIVersionException;
import org.klnusbaum.udj.NeedUpdateActivity;


/**
 * Activity used for setting up and editing UDJ accounts.
 */
public class AuthActivity extends AccountAuthenticatorActivity{

    /** The Intent flag to confirm credentials. */
    public static final String PARAM_CONFIRM_CREDENTIALS = "confirmCredentials";

    /** The Intent extra to store password. */
    public static final String PARAM_PASSWORD = "password";

    /** The Intent extra to store username. */
    public static final String PARAM_USERNAME = "username";

    /** The Intent extra to store username. */
    public static final String PARAM_AUTHTOKEN_TYPE = "authtokenType";

    /** The tag used to log to adb console. */
    private static final String TAG = "AuthenticatorActivity";
    private AccountManager mAccountManager;

    /** Keep track of the login task so can cancel it if requested */
    private UserLoginTask mAuthTask = null;

    /** Keep track of the progress dialog so we can dismiss it */
    private ProgressDialog mProgressDialog = null;

    /**
     * If set we are just checking that the user knows their credentials; this
     * doesn't cause the user's password or authToken to be changed on the
     * device.
     */
    private Boolean mConfirmCredentials = false;

    private TextView mMessage;

    private String mPassword;

    private EditText mPasswordEdit;

    /** Was the original caller asking for an entirely new account? */
    protected boolean mRequestNewAccount = false;

    private String mUsername;

    private EditText mUsernameEdit;

    private enum AuthError{NO_ERROR, AUTH_ERROR, API_VERSION_ERROR, SERVER_ERROR}

    private static class ServerAuthResult{
      public AuthError authError;
      public ServerConnection.AuthResult serverResult;

      public ServerAuthResult(ServerConnection.AuthResult serverResult){
        this.authError = AuthError.NO_ERROR;
        this.serverResult = serverResult;
      }

      public ServerAuthResult(AuthError authError){
        this.authError = authError;
        this.serverResult = null;
      }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mAccountManager = AccountManager.get(this);
        final Intent intent = getIntent();
        mUsername = intent.getStringExtra(PARAM_USERNAME);
        mRequestNewAccount = mUsername == null;
        mConfirmCredentials = 
          intent.getBooleanExtra(PARAM_CONFIRM_CREDENTIALS, false);
        Log.i(TAG, "    request new: " + mRequestNewAccount);
        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        setContentView(R.layout.login_activity);
        getWindow().setFeatureDrawableResource(
                Window.FEATURE_LEFT_ICON, android.R.drawable.ic_dialog_alert);
        mMessage = (TextView) findViewById(R.id.message);
        mUsernameEdit = (EditText) findViewById(R.id.username_edit);
        mPasswordEdit = (EditText) findViewById(R.id.password_edit);
        if (!TextUtils.isEmpty(mUsername)) mUsernameEdit.setText(mUsername);
        mMessage.setText(getMessage());

        TextView signUp = (TextView) findViewById(R.id.signup_text);
        signUp.setText(Html.fromHtml(getString(R.string.dont_have_account)));
        signUp.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /*
     * {@inheritDoc}
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(getText(R.string.authenticating));
        dialog.setIndeterminate(true);
        dialog.setCancelable(true);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                Log.i(TAG, "user cancelling authentication");
                if (mAuthTask != null) {
                    mAuthTask.cancel(true);
                }
            }
        });
        // We save off the progress dialog in a field so that we can dismiss
        // it later. We can't just call dismissDialog(0) because the system
        // can lose track of our dialog if there's an orientation change.
        mProgressDialog = dialog;
        return dialog;
    }

    /**
     * Handles onClick event on the Submit button. Sends username/password to
     * the server for authentication. The button is configured to call
     * handleLogin() in the layout XML.
     *
     * @param view The Submit button for which this method is invoked
     */
    public void handleLogin(View view) {
        if (mRequestNewAccount) {
            mUsername = mUsernameEdit.getText().toString();
        }
        mPassword = mPasswordEdit.getText().toString();
        if (TextUtils.isEmpty(mUsername) || TextUtils.isEmpty(mPassword)) {
            mMessage.setText(getMessage());
        } else {
            if(!Utils.isNetworkAvailable(this)){
                mMessage.setText(getText(R.string.no_network_connection));
            }
            else{
                // Show a progress dialog, and kick off a background task to perform
                // the user login attempt.
                showProgress();
                mAuthTask = new UserLoginTask();
                mAuthTask.execute();
            }
        }
    }

    /**
     * Called when response is received from the server for confirm credentials
     * request. See onAuthenticationResult(). Sets the
     * AccountAuthenticatorResult which is sent back to the caller.
     *
     * @param result the confirmCredentials result.
     */
    private void finishConfirmCredentials(boolean result) {
        Log.i(TAG, "finishConfirmCredentials()");
        final Account account = new Account(mUsername, Constants.ACCOUNT_TYPE);
        mAccountManager.setPassword(account, mPassword);
        final Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_BOOLEAN_RESULT, result);
        setAccountAuthenticatorResult(intent.getExtras());
        setResult(RESULT_OK, intent);
        finish();
    }

    /**
     * Called when response is received from the server for authentication
     * request. See onAuthenticationResult(). Sets the
     * AccountAuthenticatorResult which is sent back to the caller. We store the
     * authToken that's returned from the server as the 'password' for this
     * account - so we're never storing the user's actual password locally.
     *
     * @param result the confirmCredentials result.
     */
    private void finishLogin(ServerConnection.AuthResult authResult) {
        Log.i(TAG, "finishLogin()");
        final Account account = new Account(mUsername, Constants.ACCOUNT_TYPE);
        if (mRequestNewAccount) {
            mAccountManager.addAccountExplicitly(account, mPassword, null);
            ContentResolver.setIsSyncable(account, Constants.AUTHORITY, 0);
        } else {
            mAccountManager.setPassword(account, mPassword);
        }
        mAccountManager.setUserData(account, Constants.USER_ID_DATA, 
          Long.toString(authResult.userId));
        mAccountManager.setUserData(account, Constants.LAST_EVENT_ID_DATA, 
          Long.toString(Constants.NO_EVENT_ID));
        mAccountManager.setUserData(account, Constants.EVENT_STATE_DATA, 
          Integer.toString(Constants.NOT_IN_EVENT));
        final Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, mUsername);
        intent.putExtra(
          AccountManager.KEY_ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
        intent.putExtra(Constants.ACCOUNT_EXTRA, account);
        setAccountAuthenticatorResult(intent.getExtras());
        setResult(RESULT_OK, intent);
        finish();
    }

    /**
     * Called when the authentication process completes (see attemptLogin()).
     *
     * @param authToken the authentication token returned by the server, or NULL if
     *            authentication failed.
     */
    public void onAuthenticationResult(ServerAuthResult authResult){
      boolean success = (authResult.authError == AuthError.NO_ERROR);
      Log.i(TAG, "onAuthenticationResult(" + success + ")");

      // Our task is complete, so clear it out
      mAuthTask = null;

      // Hide the progress dialog
      hideProgress();

      if(success){
        if(!mConfirmCredentials){
          finishLogin(authResult.serverResult);
        }
        else{
          finishConfirmCredentials(success);
        }
      }
      else{
        switch(authResult.authError){
          case AUTH_ERROR:
            Log.e(TAG, "onAuthenticationResult: failed to authenticate");
            if (mRequestNewAccount) {
              // "Please enter a valid username/password.
              mMessage.setText(getText(R.string.bad_username_and_password));
            }
            else{
              // "Please enter a valid password." (Used when the
              // account is already in the database but the password
              // doesn't work.)
              mMessage.setText(getText(R.string.bad_password));
            }
            break;
          case API_VERSION_ERROR:
            Intent needUpdateIntent = new Intent(this, NeedUpdateActivity.class);
            startActivity(needUpdateIntent);
            break;
          default:
            mMessage.setText(getText(R.string.unknown_auth_error));
        }
      }
    }

    public void onAuthenticationCancel() {
        Log.i(TAG, "onAuthenticationCancel()");

        // Our task is complete, so clear it out
        mAuthTask = null;

        // Hide the progress dialog
        hideProgress();
    }

    /**
     * Returns the message to be displayed at the top of the login dialog box.
     */
    private CharSequence getMessage() {
        if (TextUtils.isEmpty(mUsername)) {
            // If no username, then we ask the user to log in using an
            // appropriate service.
            final CharSequence msg = getText(R.string.new_account_text);
            return msg;
        }
        if (TextUtils.isEmpty(mPassword)) {
            // We have an account but no password
            return getText(R.string.need_password);
        }
        return null;
    }

    /**
     * Shows the progress UI for a lengthy operation.
     */
    private void showProgress() {
        showDialog(0);
    }

    /**
     * Hides the progress UI for a lengthy operation.
     */
    private void hideProgress() {
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        }
    }

    /**
     * Represents an asynchronous task used to authenticate a user against the
     * SampleSync Service
     */
    public class UserLoginTask extends AsyncTask<Void, Void, ServerAuthResult> {

        @Override
        protected ServerAuthResult doInBackground(Void... params) {
            // We do the actual work of authenticating the user
            // in the NetworkUtilities class.
            try {
                return new ServerAuthResult(ServerConnection.authenticate(mUsername, mPassword));
            } catch (AuthenticationException ex) {
                Log.d(TAG, "Actual Auth error");
                return new ServerAuthResult(AuthError.AUTH_ERROR);
            } catch (APIVersionException ex) {
                Log.d(TAG, "API version error");
                return new ServerAuthResult(AuthError.API_VERSION_ERROR);
            } catch (IOException ex) {
                Log.d(TAG, "Unknonw server error");
                return new ServerAuthResult(AuthError.SERVER_ERROR);
            }
        }

        @Override
        protected void onPostExecute(
          final ServerAuthResult authResult)
        {
            // On a successful authentication, call back into the Activity to
            // communicate the authToken (or null for an error).
            onAuthenticationResult(authResult);
        }

        @Override
        protected void onCancelled() {
            // If the action was canceled (by the user clicking the cancel
            // button in the progress dialog), then call back into the
            // activity to let it know.
            onAuthenticationCancel();
        }
    }

}
