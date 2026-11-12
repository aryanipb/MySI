# R8 keep rules for the MySI release build.
#
# Each rule below exists because something outside Java bytecode (native code, or reflection on a
# name) refers to a class or member that R8 would otherwise rename or remove. They are as narrow as
# the evidence allows; the evidence is quoted next to each rule. Everything else is shrunk,
# optimised and obfuscated normally.
#
# Already covered elsewhere, so not repeated here:
# - AGP's proguard-android-optimize.txt keeps the names of every class with native methods and of
#   those methods (with the classes in their signatures), enum values()/valueOf(), Parcelable
#   CREATOR fields and @Keep members.
# - AAPT keeps the activities, receiver, provider and Application named in the manifest.
# - Libraries' own consumer rules are applied automatically: AndroidX and CameraX, kotlinx-coroutines,
#   kotlin-reflect, gson (META-INF/proguard/gson.pro) and pdfbox-android (proguard.txt: its security
#   handlers and com.tom_roush.pdfbox.pdmodel.documentinterchange.**).
#
# Not needed, checked:
# - gson elsewhere in MySI: Agent.execute only calls Gson().toJson on Map and List tool results
#   built from String, Number, Boolean, List<String> and Map<String, String>, which gson writes with
#   its built-in adapters; the web code and the model catalogue (ModelCatalog) parse with
#   JsonParser / JsonElement trees. Only the two classes kept below go through gson's reflection.
# - Enums saved by name (ModelSetupActivity's Use, Priority and Page) are read back with valueOf,
#   which proguard-android-optimize.txt keeps; Enum.name() returns the declared name even when R8
#   renames the constant's field.
# - Tool and skill names that reach the model are string literals in ToolDef and Skill, not class
#   or member names. MySI has no Class.forName, Serializable, Parcelable of its own, or JavaScript
#   interface. PdfSource's message names LinkageError subclasses, which are platform classes.

# ---------------------------------------------------------------------------------------------
# MySI's own classes that gson reads or writes by reflection, field name = JSON key.
# PendingDownload is saved to SharedPreferences (ModelStore.savePending) and read back after the
# app is killed (loadPending); renamed fields would make a saved download unreadable, including
# one saved by the previous app version, whose R8 names may differ. FitMeasurement is saved the
# same way (FitCheckRunner.save) and kept for the same reason: a stable, readable record.
-keepclassmembers class com.mysi.agent.models.PendingDownload { <fields>; }
-keepclassmembers class com.mysi.agent.models.FitMeasurement { <fields>; }

# ---------------------------------------------------------------------------------------------
# LiteRT-LM 0.18.1 (com.google.ai.edge.litertlm). The AAR ships no consumer rules.
# liblitertlm_jni.so binds its natives statically (Java_com_google_ai_edge_litertlm_LiteRtLmJni_*,
# NativeLibraryLoader_nativeCheckLoaded), which the default native-method rule covers. Beyond that,
# the .so calls back into Java by name: the class names and method names and descriptors below are
# the ones found in its string table (strings liblitertlm_jni.so).

# FindClass + GetMethodID("<init>"): the native layer builds these and throws the exception.
-keep class com.google.ai.edge.litertlm.BenchmarkInfo { <init>(double, double, int, int, double, double); }
-keep class com.google.ai.edge.litertlm.EmbeddingResponse { <init>(float[]); }
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { <init>(java.lang.String); }

# FindClass + IsInstanceOf, then GetMethodID("getText" / "getBytes") on prompt input parts.
-keep class com.google.ai.edge.litertlm.InputData$Text { java.lang.String getText(); }
-keep class com.google.ai.edge.litertlm.InputData$Image { byte[] getBytes(); }
-keep class com.google.ai.edge.litertlm.InputData$Audio { byte[] getBytes(); }

# GetMethodID on the config objects passed to nativeCreateSession, nativeCreateConversation and
# nativeSendMessage(Async). Only the getters are called from native code. -keep rather than
# -keepclassmembers: MySI never creates the last three today, so R8 would drop their getters and
# native code would only ever see null; keeping them unconditionally means a future caller (or a
# LiteRT-LM default) that passes one cannot meet a missing method.
-keep class com.google.ai.edge.litertlm.SamplerConfig {
    int getTopK();
    double getTopP();
    double getTemperature();
    int getSeed();
}
-keep class com.google.ai.edge.litertlm.ThinkingConfig {
    boolean getEnableThinking();
    int getThinkingTokenBudget();
}
-keep class com.google.ai.edge.litertlm.RepetitionPenaltyConfig {
    java.lang.Float getRepetitionPenalty();
    java.lang.Float getPresencePenalty();
    java.lang.Float getFrequencyPenalty();
    java.lang.Integer getWindowSize();
}
-keep class com.google.ai.edge.litertlm.NoRepeatNgramConfig {
    java.lang.Integer getNoRepeatNgramSize();
    java.lang.Integer getWindowSize();
}

# Streaming callbacks: native code calls onMessage/onNext, onDone and onError on the callback object
# it was given (Conversation$JniMessageCallbackImpl, Session$JniInferenceCallbackImpl). Keeping the
# interface methods and every implementation keeps the names the .so asks for.
-keep interface com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback { *; }
-keep interface com.google.ai.edge.litertlm.LiteRtLmJni$JniInferenceCallback { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback {
    void onMessage(java.lang.String);
    void onDone();
    void onError(int, java.lang.String);
}
-keepclassmembers class * implements com.google.ai.edge.litertlm.LiteRtLmJni$JniInferenceCallback {
    void onNext(java.lang.String);
    void onDone();
    void onError(int, java.lang.String);
}

# ---------------------------------------------------------------------------------------------
# MediaPipe Tasks 0.10.35 (vision, audio, core). The AARs ship no consumer rules. The four rules
# below are MediaPipe's own, from mediapipe/java/com/google/mediapipe/framework/proguard.pgcfg at
# tag v0.10.35, and match what libmediapipe_tasks_jni.so looks up by name: Packet.create /
# getNativeHandle / release, PacketCreator.releaseWithSyncToken, MediaPipeException(int, byte[]),
# ProtoUtil$SerializedMessage.typeName / value, PacketListCallback.process(List).
-keep public interface com.google.mediapipe.framework.* {
    public *;
}
-keep public class com.google.mediapipe.framework.Packet {
    public static *** create(***);
    public long getNativeHandle();
    public void release();
}
-keep public class com.google.mediapipe.framework.PacketCreator {
    *** releaseWithSyncToken(...);
}
-keep public class com.google.mediapipe.framework.MediaPipeException {
    <init>(int, byte[]);
}
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }

# protobuf-javalite (MediaPipe's task options and results are lite protos). The lite runtime finds
# message fields by name through reflection (MessageSchema), so renamed fields fail with "Field
# {NAME}_ for {CLASS} not found". This is the rule protobuf documents for R8 (java/lite.md, v26.1,
# the runtime MediaPipe 0.10.35 resolves to); protobuf-javalite ships no rules of its own. It is
# kept in its documented, broad form on purpose: the audio classifier's protos are not exercised
# on the emulator, so a narrower variant would be unproven there.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ---------------------------------------------------------------------------------------------
# pdfbox-android 2.0.27.0. Its consumer rules keep the security handlers and the
# documentinterchange package. PDNumberTreeNode.convertCOSToPD also builds values with
# getDeclaredConstructor(COSDictionary) on the class it was given; the only two classes it is given
# are PDParentTreeValue (covered by the consumer rules) and PDPageLabelRange (page labels), whose
# constructor is kept here.
-keepclassmembers class com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange {
    <init>(com.tom_roush.pdfbox.cos.COSDictionary);
}

# Classes pdfbox-android refers to but MySI deliberately does not ship. These are exactly the
# lines R8 listed in missing_rules.txt; each reference is only reached on a path MySI already
# handles without them, and R8 leaves the reference in place, so the behaviour matches the
# un-minified build (a NoClassDefFoundError that the caller catches).
# - BouncyCastle (excluded in app/build.gradle.kts): only PublicKeySecurityHandler, which opens
#   certificate-locked PDFs; PdfSource refuses those with a plain message instead.
-dontwarn org.bouncycastle.asn1.x500.X500Name
-dontwarn org.bouncycastle.cert.X509CertificateHolder
-dontwarn org.bouncycastle.cms.CMSEnvelopedData
-dontwarn org.bouncycastle.cms.CMSException
-dontwarn org.bouncycastle.cms.KeyTransRecipientId
-dontwarn org.bouncycastle.cms.Recipient
-dontwarn org.bouncycastle.cms.RecipientId
-dontwarn org.bouncycastle.cms.RecipientInformation
-dontwarn org.bouncycastle.cms.RecipientInformationStore
-dontwarn org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
-dontwarn org.bouncycastle.util.Arrays
# - JP2ForAndroid (an optional pdfbox-android dependency, never a MySI one): JPXFilter.readJPX
#   first checks Class.forName("com.gemalto.jp2.JP2Decoder") and, when it is missing, throws
#   MissingImageReaderException (an IOException) before touching it. MySI only extracts text,
#   which does not decode images anyway.
-dontwarn com.gemalto.jp2.JP2Decoder
