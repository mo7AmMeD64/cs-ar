#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include <curl/curl.h>
#include <curl/easy.h>

typedef CURLcode (*imp_global_init_t)(long);
typedef void (*imp_global_cleanup_t)(void);
typedef CURL *(*imp_easy_init_t)(void);
typedef void (*imp_easy_cleanup_t)(CURL *);
typedef CURLcode (*imp_easy_impersonate_t)(CURL *, const char *, int);
typedef CURLcode (*imp_easy_setopt_int_t)(CURL *, CURLoption, long);
typedef CURLcode (*imp_easy_setopt_str_t)(CURL *, CURLoption, const char *);
typedef CURLcode (*imp_easy_setopt_ptr_t)(CURL *, CURLoption, void *);
typedef CURLcode (*imp_easy_setopt_slist_t)(CURL *, CURLoption, struct curl_slist *);
typedef CURLcode (*imp_easy_perform_t)(CURL *);
typedef CURLcode (*imp_easy_getinfo_long_t)(CURL *, CURLINFO, long *);
typedef const char *(*imp_easy_strerror_t)(CURLcode);
typedef struct curl_slist *(*imp_slist_append_t)(struct curl_slist *, const char *);
typedef void (*imp_slist_free_all_t)(struct curl_slist *);

static imp_global_init_t    p_global_init;
static imp_global_cleanup_t p_global_cleanup;
static imp_easy_init_t      p_easy_init;
static imp_easy_cleanup_t   p_easy_cleanup;
static imp_easy_impersonate_t p_easy_impersonate;
static imp_easy_setopt_int_t  p_setopt_int;
static imp_easy_setopt_str_t  p_setopt_str;
static imp_easy_setopt_ptr_t  p_setopt_ptr;
static imp_easy_setopt_slist_t p_setopt_slist;
static imp_easy_perform_t    p_perform;
static imp_easy_getinfo_long_t p_getinfo_long;
static imp_easy_strerror_t   p_strerror;
static imp_slist_append_t    p_slist_append;
static imp_slist_free_all_t  p_slist_free_all;

static void *g_lib = NULL;
static char g_ca_path[512] = {0};
static int g_global_inited = 0;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static int g_last_status = 0;
static char g_last_error[256] = {0};
static int g_last_http = 0;

struct buf {
    unsigned char *data;
    size_t len;
    size_t cap;
};

static size_t write_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    struct buf *b = (struct buf *)userdata;
    size_t n = size * nmemb;
    if (b->len + n > b->cap) {
        size_t newcap = b->cap ? b->cap : 65536;
        while (b->len + n > newcap) newcap <<= 1;
        unsigned char *nd = (unsigned char *)realloc(b->data, newcap);
        if (!nd) return 0;
        b->data = nd;
        b->cap = newcap;
    }
    memcpy(b->data + b->len, ptr, n);
    b->len += n;
    return n;
}

static void set_err(const char *msg) {
    snprintf(g_last_error, sizeof(g_last_error), "%s", msg);
}

static jstring to_jstr(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    return (*env)->NewStringUTF(env, s);
}

/*
 * Class:     com_stardima_BrowserFetch
 * Method:    setNativePath
 * Signature: (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL
Java_com_stardima_BrowserFetch_setNativePath(JNIEnv *env, jclass clazz,
                                             jstring libPath, jstring caPath,
                                             jstring cxxPath) {
    const char *lp = libPath ? (*env)->GetStringUTFChars(env, libPath, NULL) : NULL;
    const char *cp = caPath ? (*env)->GetStringUTFChars(env, caPath, NULL) : NULL;
    const char *xp = cxxPath ? (*env)->GetStringUTFChars(env, cxxPath, NULL) : NULL;

    /* libcurl-impersonate is linked against the shared C++ runtime; load it
     * globally first so the dynamic linker resolves it by soname. */
    if (xp && xp[0]) {
        dlopen(xp, RTLD_NOW | RTLD_GLOBAL);
    }

    void *h = dlopen(lp, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char *err = dlerror();
        set_err(err ? err : "dlopen failed");
        if (lp) (*env)->ReleaseStringUTFChars(env, libPath, lp);
        if (cp) (*env)->ReleaseStringUTFChars(env, caPath, cp);
        if (xp) (*env)->ReleaseStringUTFChars(env, cxxPath, xp);
        return JNI_FALSE;
    }
    g_lib = h;
    p_global_init      = (imp_global_init_t)dlsym(h, "curl_global_init");
    p_global_cleanup   = (imp_global_cleanup_t)dlsym(h, "curl_global_cleanup");
    p_easy_init        = (imp_easy_init_t)dlsym(h, "curl_easy_init");
    p_easy_cleanup     = (imp_easy_cleanup_t)dlsym(h, "curl_easy_cleanup");
    p_easy_impersonate = (imp_easy_impersonate_t)dlsym(h, "curl_easy_impersonate");
    p_setopt_int       = (imp_easy_setopt_int_t)dlsym(h, "curl_easy_setopt");
    p_setopt_str       = (imp_easy_setopt_str_t)dlsym(h, "curl_easy_setopt");
    p_setopt_ptr       = (imp_easy_setopt_ptr_t)dlsym(h, "curl_easy_setopt");
    p_setopt_slist     = (imp_easy_setopt_slist_t)dlsym(h, "curl_easy_setopt");
    p_perform          = (imp_easy_perform_t)dlsym(h, "curl_easy_perform");
    p_getinfo_long     = (imp_easy_getinfo_long_t)dlsym(h, "curl_easy_getinfo");
    p_strerror         = (imp_easy_strerror_t)dlsym(h, "curl_easy_strerror");
    p_slist_append     = (imp_slist_append_t)dlsym(h, "curl_slist_append");
    p_slist_free_all   = (imp_slist_free_all_t)dlsym(h, "curl_slist_free_all");

    if (!p_easy_init || !p_easy_impersonate || !p_perform ||
        !p_setopt_int || !p_setopt_str || !p_setopt_ptr || !p_setopt_slist ||
        !p_getinfo_long || !p_strerror || !p_global_init || !p_global_cleanup ||
        !p_easy_cleanup || !p_slist_append || !p_slist_free_all) {
        set_err("dlsym failed: missing curl symbol");
        if (lp) (*env)->ReleaseStringUTFChars(env, libPath, lp);
        if (cp) (*env)->ReleaseStringUTFChars(env, caPath, cp);
        if (xp) (*env)->ReleaseStringUTFChars(env, cxxPath, xp);
        return JNI_FALSE;
    }
    if (cp) {
        snprintf(g_ca_path, sizeof(g_ca_path), "%s", cp);
        (*env)->ReleaseStringUTFChars(env, caPath, cp);
    }
    if (lp) (*env)->ReleaseStringUTFChars(env, libPath, lp);
    if (xp) (*env)->ReleaseStringUTFChars(env, cxxPath, xp);

    if (!g_global_inited) {
        pthread_mutex_lock(&g_mutex);
        if (!g_global_inited) {
            p_global_init(CURL_GLOBAL_DEFAULT);
            g_global_inited = 1;
        }
        pthread_mutex_unlock(&g_mutex);
    }
    return JNI_TRUE;
}

/*
 * Class:     com_mehdigm_stardima_BrowserFetch
 * Method:    fetch
 * Signature: (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;)[B
 */
JNIEXPORT jbyteArray JNICALL
Java_com_stardima_BrowserFetch_fetch(JNIEnv *env, jclass clazz,
                                             jstring url, jstring referer,
                                             jstring ua, jint timeoutSec,
                                             jobjectArray headers) {
    g_last_status = 0;
    g_last_http = 0;
    set_err("");

    if (!g_lib) {
        set_err("libcurl not loaded (setNativePath first)");
        return NULL;
    }

    const char *curl = url ? (*env)->GetStringUTFChars(env, url, NULL) : NULL;
    const char *ref  = referer ? (*env)->GetStringUTFChars(env, referer, NULL) : NULL;
    const char *agent = ua ? (*env)->GetStringUTFChars(env, ua, NULL) : NULL;

    CURL *h = p_easy_init();
    if (!h) {
        set_err("curl_easy_init failed");
        if (curl) (*env)->ReleaseStringUTFChars(env, url, curl);
        if (ref) (*env)->ReleaseStringUTFChars(env, referer, ref);
        if (agent) (*env)->ReleaseStringUTFChars(env, ua, agent);
        return NULL;
    }

    p_easy_impersonate(h, "chrome", 1);
    p_setopt_str(h, CURLOPT_URL, curl);
    if (ref && ref[0]) p_setopt_str(h, CURLOPT_REFERER, ref);
    if (agent && agent[0]) p_setopt_str(h, CURLOPT_USERAGENT, agent);
    p_setopt_str(h, CURLOPT_ACCEPT_ENCODING, "");   /* decompress gzip/br/zstd */
    p_setopt_int(h, CURLOPT_FOLLOWLOCATION, 1L);
    p_setopt_int(h, CURLOPT_MAXREDIRS, 5L);
    if (timeoutSec > 0) p_setopt_int(h, CURLOPT_TIMEOUT, (long)timeoutSec);
    p_setopt_int(h, CURLOPT_CONNECTTIMEOUT, 10L);
    if (g_ca_path[0]) p_setopt_str(h, CURLOPT_CAINFO, g_ca_path);

    struct curl_slist *hdrs = NULL;
    if (headers) {
        jsize n = (*env)->GetArrayLength(env, headers);
        for (jsize i = 0; i < n; i++) {
            jstring hs = (jstring)(*env)->GetObjectArrayElement(env, headers, i);
            if (!hs) continue;
            const char *hv = (*env)->GetStringUTFChars(env, hs, NULL);
            if (hv) {
                hdrs = p_slist_append(hdrs, hv);
                (*env)->ReleaseStringUTFChars(env, hs, hv);
            }
            (*env)->DeleteLocalRef(env, hs);
        }
    }
    /* Only set an explicit header list when the caller actually added one:
     * curl-impersonate installs the full browser header set (sec-ch-ua,
     * Accept, User-Agent, sec-fetch-*) during impersonate(), and setting
     * CURLOPT_HTTPHEADER here would silently replace that whole list. */
    if (hdrs) p_setopt_slist(h, CURLOPT_HTTPHEADER, hdrs);

    struct buf b = {0, 0, 0};
    p_setopt_ptr(h, CURLOPT_WRITEFUNCTION, write_cb);
    p_setopt_ptr(h, CURLOPT_WRITEDATA, &b);

    pthread_mutex_lock(&g_mutex);
    CURLcode rc = p_perform(h);
    pthread_mutex_unlock(&g_mutex);

    long http = 0;
    p_getinfo_long(h, CURLINFO_RESPONSE_CODE, &http);
    g_last_http = (int)http;

    jbyteArray out = NULL;
    if (rc == CURLE_OK && b.len > 0) {
        out = (*env)->NewByteArray(env, (jsize)b.len);
        if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)b.len, (const jbyte *)b.data);
        g_last_status = (int)http;
    } else if (rc == CURLE_OK) {
        /* HTTP error with no body (e.g. 403 empty) */
        g_last_status = (int)http;
    } else {
        g_last_status = -1;
        const char *es = p_strerror(rc);
        snprintf(g_last_error, sizeof(g_last_error), "%s", es ? es : "curl error");
    }

    if (hdrs) p_slist_free_all(hdrs);
    p_easy_cleanup(h);
    free(b.data);

    if (curl) (*env)->ReleaseStringUTFChars(env, url, curl);
    if (ref) (*env)->ReleaseStringUTFChars(env, referer, ref);
    if (agent) (*env)->ReleaseStringUTFChars(env, ua, agent);

    return out;
}

JNIEXPORT jint JNICALL
Java_com_stardima_BrowserFetch_lastStatus(JNIEnv *env, jclass clazz) {
    return (jint)g_last_status;
}

JNIEXPORT jint JNICALL
Java_com_stardima_BrowserFetch_lastHttp(JNIEnv *env, jclass clazz) {
    return (jint)g_last_http;
}

JNIEXPORT jstring JNICALL
Java_com_stardima_BrowserFetch_lastError(JNIEnv *env, jclass clazz) {
    return to_jstr(env, g_last_error);
}

JNIEXPORT jstring JNICALL
Java_com_stardima_BrowserFetch_version(JNIEnv *env, jclass clazz) {
    return to_jstr(env, "browserfetch/0.1 (curl-impersonate chrome)");
}