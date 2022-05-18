package com.bae.dialogflowbot;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bae.dialogflowbot.adapters.ChatAdapter;
import com.bae.dialogflowbot.helpers.SendMessageInBg;
import com.bae.dialogflowbot.interfaces.BotReply;
import com.bae.dialogflowbot.models.Message;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.dialogflow.v2.DetectIntentResponse;
import com.google.cloud.dialogflow.v2.QueryInput;
import com.google.cloud.dialogflow.v2.SessionName;
import com.google.cloud.dialogflow.v2.SessionsClient;
import com.google.cloud.dialogflow.v2.SessionsSettings;
import com.google.cloud.dialogflow.v2.TextInput;
import com.google.common.collect.Lists;
import com.google.protobuf.Value;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements BotReply, TextToSpeech.OnInitListener{

  RecyclerView chatView;
  ChatAdapter chatAdapter;
  List<Message> messageList = new ArrayList<>();
  EditText editMessage;
  ImageButton btnSend;
  ImageButton btnMicro;
  private static final int REQUEST_CODE_SPEECH_INPUT = 1000;
  private boolean ttsReady = false;
  private TextToSpeech tts;

  //dialogFlow
  private SessionsClient sessionsClient;
  private SessionName sessionName;
  private String uuid = UUID.randomUUID().toString();
  private String TAG = "mainactivity";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    chatView = findViewById(R.id.chatView);
    editMessage = findViewById(R.id.editMessage);
    btnSend = findViewById(R.id.btnSend);
    btnMicro = findViewById(R.id.btnMicro);

    chatAdapter = new ChatAdapter(messageList, this);
    chatView.setAdapter(chatAdapter);
    tts = new TextToSpeech(this, this);

    btnMicro.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        speak();
      }
    });

    btnSend.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) {

        String message = editMessage.getText().toString();
        if (!message.isEmpty()) {
          messageList.add(new Message(message, false));
          editMessage.setText("");
          sendMessageToBot(message);
          Objects.requireNonNull(chatView.getAdapter()).notifyDataSetChanged();
          Objects.requireNonNull(chatView.getLayoutManager())
              .scrollToPosition(messageList.size() - 1);
        } else {
          Toast.makeText(MainActivity.this, "Escribe aqui...", Toast.LENGTH_SHORT).show();
        }
      }
    });
    setUpBot();
  }

  @Override
  public void onInit(int i) {
    ttsReady = true;
    tts.setLanguage(new Locale("spa", "ES"));
  }
  //microfono
  private void speak() {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime");
    try {
      startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT);
    } catch (Exception e){
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    switch (requestCode){
      case REQUEST_CODE_SPEECH_INPUT:{
        if (resultCode == RESULT_OK && null !=data){
          ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
          editMessage.setText(result.get(0));

        }
      }
    }

  }

  //bot
  private void setUpBot() {
    try {
      InputStream stream = this.getResources().openRawResource(R.raw.cliente_google);
      GoogleCredentials credentials = GoogleCredentials.fromStream(stream)
          .createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));
      String projectId = ((ServiceAccountCredentials) credentials).getProjectId();

      SessionsSettings.Builder settingsBuilder = SessionsSettings.newBuilder();
      SessionsSettings sessionsSettings = settingsBuilder.setCredentialsProvider(
          FixedCredentialsProvider.create(credentials)).build();
      sessionsClient = SessionsClient.create(sessionsSettings);
      sessionName = SessionName.of(projectId, uuid);

      Log.d(TAG, "projectId : " + projectId);
    } catch (Exception e) {
      Log.d(TAG, "setUpBot: " + e.getMessage());
    }
  }
  //enviar mensaje
  private void sendMessageToBot(String message) {
    QueryInput input = QueryInput.newBuilder()
        .setText(TextInput.newBuilder().setText(message).setLanguageCode("en-US")).build();
    new SendMessageInBg(this, sessionName, sessionsClient, input).execute();
  }
  //guardar cita
  @RequiresApi(api = Build.VERSION_CODES.O)
  @Override
  public void callback(DetectIntentResponse returnResponse) {
     if(returnResponse!=null) {

       String botReply = returnResponse.getQueryResult().getFulfillmentText();
       String intent = returnResponse.getQueryResult().getIntent().getDisplayName();
       if(intent.equals("Citas")){
         Map<String, Value> params = returnResponse.getQueryResult().getParameters().getFieldsMap();
         Value nombreResponse = params.get("name");
         String nombre = String.valueOf(nombreResponse.getStringValue());
         Value diaResponse = params.get("date");
         String dia = String.valueOf(diaResponse.getStringValue());
         Value horaResponse = params.get("time");
         String hora = String.valueOf(horaResponse.getStringValue());

         if(!dia.equals("") && !nombre.equals("") && !hora.equals("") )
           saveInCalendar(dia, nombre, hora);
       }

       if(!botReply.isEmpty()){
         messageList.add(new Message(botReply, true));
         chatAdapter.notifyDataSetChanged();
         tts.speak(botReply, TextToSpeech.QUEUE_ADD, null, null);
         Objects.requireNonNull(chatView.getLayoutManager()).scrollToPosition(messageList.size() - 1);

       }else {
         Toast.makeText(this, "Algo salio mal", Toast.LENGTH_SHORT).show();
       }
     } else {
       Toast.makeText(this, "No se pudo conectar", Toast.LENGTH_SHORT).show();
     }
  }

  @RequiresApi(api = Build.VERSION_CODES.O)

  public void saveInCalendar(String date, String name, String time) {
    String fecha = date.substring(0,10);
    String hora = time.substring(10);
    String fechaFinal = fecha + hora;

    DateTimeFormatter isoDateFormatter = DateTimeFormatter.ISO_DATE_TIME;
    LocalDateTime ldate = LocalDateTime.parse(fechaFinal, isoDateFormatter);
    Date rDate = Date.from(ldate.atZone(ZoneId.of("UTC+2")).toInstant());
    long begin = rDate.getTime();

    Intent intent = new Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, name)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
    if (intent.resolveActivity(getPackageManager()) != null) {
      startActivity(intent);
    }
  }
}

