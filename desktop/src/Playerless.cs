// Playerless per Windows. L'app vera e' la pagina in web/, che gira in una finestra di Firefox
// senza barre; questo programma la serve su http://localhost:47380 e fa per lei quello che una
// pagina non puo': le richieste a JioSaavn e YouTube con le loro intestazioni, i file sul disco,
// adb per importare dal telefono. Si chiude da solo quando la finestra non c'e' piu'.
//
// C# 5, per il compilatore che Windows ha gia' (vedi build.bat): niente da installare.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Management;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace Playerless
{
    static class App
    {
        const int Port = 47380;
        const string Origin = "http://localhost:47380";
        const string Package = "com.playerless";
        const string WebUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
        // The page is gone this long (closed, or reloading) before the server gives up.
        static readonly TimeSpan Grace = TimeSpan.FromSeconds(20);
        // Firefox's first start on a new profile is slow.
        static readonly TimeSpan StartupGrace = TimeSpan.FromSeconds(90);

        // Only these sites, so the page can't be turned into a proxy for anything else.
        static readonly string[] Hosts = {
            "jiosaavn.com", "saavncdn.com", "youtube.com", "googlevideo.com", "ytimg.com",
            "ggpht.com", "googleusercontent.com",
        };
        static readonly string[] DataNames = { "settings", "equalizer", "playlists", "plays", "downloads", "youtube" };
        static readonly Regex SafeName = new Regex("^[A-Za-z0-9_-]+$");
        static readonly Regex MusicName = new Regex(@"^[A-Za-z0-9_-]+\.(m4a|jpg)$");
        static readonly Regex CacheName = new Regex(@"^[A-Za-z0-9_-]+\.js$");
        static readonly Regex StaticName = new Regex(@"^[A-Za-z0-9_.-]+$");

        static string root, dataDir, musicDir, cacheDir, profileDir, webDir, logFile;
        static bool server; // --server: no window, never closes by itself. For development.
        static readonly DateTime started = DateTime.UtcNow;
        static DateTime lastPage = DateTime.UtcNow;
        static readonly object pagesLock = new object();
        static readonly List<HttpListenerResponse> pages = new List<HttpListenerResponse>();
        static readonly object dataLock = new object();
        static readonly object logLock = new object();
        static string phone; // The adb serial of the last phone found.
        static string chosenPhone; // --phone <serial>, when more than one is connected.

        static JavaScriptSerializer Json()
        {
            var json = new JavaScriptSerializer();
            json.MaxJsonLength = int.MaxValue;
            return json;
        }

        [STAThread]
        static int Main(string[] args)
        {
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--server") server = true;
                else if (args[i] == "--web" && i + 1 < args.Length) webDir = Path.GetFullPath(args[++i]);
                else if (args[i] == "--data" && i + 1 < args.Length) root = Path.GetFullPath(args[++i]);
                else if (args[i] == "--phone" && i + 1 < args.Length) chosenPhone = args[++i];
            }
            if (root == null) root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Playerless");
            dataDir = Folder("data");
            musicDir = Folder("music");
            cacheDir = Folder("cache");
            profileDir = Path.Combine(root, "firefox");
            logFile = Path.Combine(root, "log.txt");
            try { if (File.Exists(logFile) && new FileInfo(logFile).Length > 1 << 20) File.Delete(logFile); } catch (IOException) { }
            AppDomain.CurrentDomain.UnhandledException += (sender, e) => Log("Errore non gestito: " + e.ExceptionObject);

            // Built without an app.config, .NET would offer only TLS 1.0.
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)12288; // TLS 1.3
            ServicePointManager.DefaultConnectionLimit = 32;
            ServicePointManager.Expect100Continue = false;

            var listener = new HttpListener();
            listener.Prefixes.Add(Origin + "/");
            try
            {
                listener.Start();
            }
            catch (HttpListenerException)
            {
                // Already running: that one shows its window.
                if (Call("/api/show") == "ok") return 0;
                Fail("La porta " + Port + " e' occupata da un altro programma: Playerless non puo' partire.");
                return 1;
            }
            Log("Avviato, dati in " + root);

            if (!server)
            {
                var watchdog = new Thread(Watchdog);
                watchdog.IsBackground = true;
                watchdog.Start();
                OpenWindow();
            }
            while (true)
            {
                HttpListenerContext context;
                try
                {
                    context = listener.GetContext();
                }
                catch (Exception e)
                {
                    Log("Richiesta persa: " + e.Message);
                    if (listener.IsListening) continue;
                    Log("Server fermo");
                    return 1;
                }
                ThreadPool.QueueUserWorkItem(Handle, context);
            }
        }

        static string Folder(string name)
        {
            var path = Path.Combine(root, name);
            Directory.CreateDirectory(path);
            return path;
        }

        // --- The window ------------------------------------------------------------------------

        static void OpenWindow()
        {
            // Our Firefox is already up: its page reconnects by itself.
            if (FirefoxRunning()) return;
            var firefox = FindFirefox();
            if (firefox == null)
            {
                Fail("Non trovo Firefox. Playerless per PC lo usa come finestra: installalo da mozilla.org e riprova.");
                Environment.Exit(1);
            }
            PrepareProfile();
            var start = new ProcessStartInfo(firefox, "-no-remote -new-instance -profile \"" + profileDir + "\" " + Origin + "/");
            start.UseShellExecute = false;
            Process.Start(start);
            Log("Aperta la finestra");
        }

        static string FindFirefox()
        {
            var candidates = new List<string>();
            foreach (var hive in new[] { Microsoft.Win32.Registry.CurrentUser, Microsoft.Win32.Registry.LocalMachine })
            {
                using (var key = hive.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe"))
                {
                    if (key != null && key.GetValue(null) is string) candidates.Add((string)key.GetValue(null));
                }
            }
            candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Mozilla Firefox\firefox.exe"));
            candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Mozilla Firefox\firefox.exe"));
            candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Mozilla Firefox\firefox.exe"));
            foreach (var path in candidates)
            {
                if (File.Exists(path.Trim('"'))) return path.Trim('"');
            }
            return null;
        }

        static bool FirefoxRunning()
        {
            try
            {
                using (var search = new ManagementObjectSearcher("SELECT CommandLine FROM Win32_Process WHERE Name = 'firefox.exe'"))
                {
                    foreach (ManagementObject process in search.Get())
                    {
                        var line = process["CommandLine"] as string;
                        if (line != null && line.IndexOf(profileDir, StringComparison.OrdinalIgnoreCase) >= 0) return true;
                    }
                }
            }
            catch (Exception e)
            {
                Log("Elenco dei processi non disponibile: " + e.Message);
            }
            return false;
        }

        // A Firefox of its own: no toolbars, no welcome pages, music that may start by itself.
        static void PrepareProfile()
        {
            Directory.CreateDirectory(Path.Combine(profileDir, "chrome"));
            var prefs = new[] {
                "toolkit.legacyUserProfileCustomizations.stylesheets\", true",
                // Its own group on the taskbar, apart from the user's Firefox.
                "taskbar.grouping.useprofile\", true",
                "browser.tabs.inTitlebar\", 0",
                "browser.toolbars.bookmarks.visibility\", \"never\"",
                "sidebar.revamp\", false",
                "sidebar.verticalTabs\", false",
                "browser.shell.checkDefaultBrowser\", false",
                "browser.aboutwelcome.enabled\", false",
                "browser.startup.homepage_override.mstone\", \"ignore\"",
                "startup.homepage_welcome_url\", \"\"",
                "startup.homepage_welcome_url.additional\", \"\"",
                "browser.startup.page\", 0",
                "browser.sessionstore.resume_from_crash\", false",
                "browser.tabs.warnOnClose\", false",
                "browser.warnOnQuit\", false",
                "browser.translations.automaticallyPopup\", false",
                "media.autoplay.default\", 0",
                "media.autoplay.blocking_policy\", 0",
                "media.hardwaremediakeys.enabled\", true",
                "signon.rememberSignons\", false",
                "extensions.pocket.enabled\", false",
                "browser.uitour.enabled\", false",
                "app.normandy.enabled\", false",
                "toolkit.telemetry.reportingpolicy.firstRun\", false",
                "datareporting.policy.dataSubmissionPolicyBypassNotification\", true",
                "datareporting.healthreport.uploadEnabled\", false",
            };
            var userJs = new StringBuilder("// Scritto da Playerless.exe a ogni avvio.\r\n");
            foreach (var pref in prefs) userJs.Append("user_pref(\"").Append(pref).Append(");\r\n");
            File.WriteAllText(Path.Combine(profileDir, "user.js"), userJs.ToString());
            File.WriteAllText(Path.Combine(profileDir, @"chrome\userChrome.css"),
                "/* Playerless: solo la pagina, niente barre di Firefox. */\r\n" +
                "#navigator-toolbox { visibility: collapse !important; }\r\n" +
                "#sidebar-main, #sidebar-box, #sidebar-splitter { display: none !important; }\r\n");

            // The first window: a sensible size in the middle of the screen. Firefox remembers the rest.
            var xulstore = Path.Combine(profileDir, "xulstore.json");
            if (!File.Exists(xulstore))
            {
                var area = Screen.PrimaryScreen.WorkingArea;
                int width = Math.Min(1180, area.Width), height = Math.Min(780, area.Height);
                File.WriteAllText(xulstore, string.Format(
                    "{{\"chrome://browser/content/browser.xhtml\":{{\"main-window\":{{\"screenX\":\"{0}\",\"screenY\":\"{1}\",\"width\":\"{2}\",\"height\":\"{3}\",\"sizemode\":\"normal\"}}}}}}",
                    area.Left + (area.Width - width) / 2, area.Top + (area.Height - height) / 2, width, height));
            }
        }

        // The page keeps /api/events open; once it has been gone a while, so is the app.
        static void Watchdog()
        {
            while (true)
            {
                Thread.Sleep(5000);
                int open;
                lock (pagesLock)
                {
                    foreach (var page in pages.ToArray())
                    {
                        if (!SendEvent(page, ": ping\n\n")) pages.Remove(page);
                    }
                    open = pages.Count;
                }
                var now = DateTime.UtcNow;
                if (open > 0) lastPage = now;
                else if (now - lastPage > Grace && now - started > StartupGrace)
                {
                    Log("Finestra chiusa, esco");
                    Environment.Exit(0);
                }
            }
        }

        static bool SendEvent(HttpListenerResponse page, string text)
        {
            try
            {
                var bytes = Encoding.UTF8.GetBytes(text);
                page.OutputStream.Write(bytes, 0, bytes.Length);
                page.OutputStream.Flush();
                return true;
            }
            catch (Exception)
            {
                try { page.Abort(); } catch (Exception) { }
                return false;
            }
        }

        // A second start lands here: bring the window up, or open it again.
        static void Show()
        {
            lock (pagesLock)
            {
                if (pages.Count > 0)
                {
                    foreach (var page in pages.ToArray())
                    {
                        if (!SendEvent(page, "event: show\ndata: 1\n\n")) pages.Remove(page);
                    }
                    if (pages.Count > 0) return;
                }
            }
            OpenWindow();
        }

        // --- Requests --------------------------------------------------------------------------

        static void Handle(object state)
        {
            var context = (HttpListenerContext)state;
            var request = context.Request;
            var response = context.Response;
            bool keepOpen = false;
            try
            {
                if (!Trusted(request))
                {
                    Text(response, 403, "Vietato");
                    return;
                }
                var path = request.Url.AbsolutePath;
                if (path == "/api/ping") Text(response, 200, "playerless");
                else if (path == "/api/events") keepOpen = Events(response);
                else if (path == "/api/show") { Show(); Text(response, 200, "ok"); }
                else if (path.StartsWith("/api/data/")) Data(request, response, path.Substring(10));
                else if (path.StartsWith("/api/cache/")) Cache(request, response, path.Substring(11));
                else if (path == "/api/fetch") Fetch(request, response);
                else if (path == "/api/des") Des(request, response);
                else if (path == "/api/stream") Stream(request, response);
                else if (path == "/api/download") Download(request, response);
                else if (path.StartsWith("/music/")) Music(request, response, path.Substring(7));
                else if (path == "/api/phone/data") PhoneData(response);
                else if (path == "/api/phone/file") PhoneFile(request, response);
                else if (path == "/api/log") { Log("Pagina: " + Body(request)); Text(response, 200, "ok"); }
                else Static(response, path == "/" ? "index.html" : path.Substring(1));
            }
            catch (Exception e)
            {
                // Mostly the page closing a stream it no longer needs.
                if (!(e is HttpListenerException)) Log(request.Url.AbsolutePath + ": " + e);
                try { Text(response, 500, e.Message); } catch (Exception) { }
            }
            finally
            {
                if (!keepOpen)
                {
                    try { response.Close(); } catch (Exception) { }
                }
            }
        }

        // Only our own page: other sites open in a browser can't call in, not even by a link or
        // a rebound DNS name.
        static bool Trusted(HttpListenerRequest request)
        {
            var host = request.Headers["Host"];
            if (host != "localhost:" + Port) return false;
            var origin = request.Headers["Origin"];
            if (origin != null && origin != Origin) return false;
            var site = request.Headers["Sec-Fetch-Site"];
            return site == null || site == "same-origin" || site == "none";
        }

        static bool Events(HttpListenerResponse response)
        {
            response.ContentType = "text/event-stream";
            response.AddHeader("Cache-Control", "no-store");
            response.SendChunked = true;
            if (!SendEvent(response, ": ciao\n\n")) return false;
            lock (pagesLock) pages.Add(response);
            lastPage = DateTime.UtcNow;
            return true;
        }

        // The page's state, one JSON file each, written whole.
        static void Data(HttpListenerRequest request, HttpListenerResponse response, string name)
        {
            if (Array.IndexOf(DataNames, name) < 0)
            {
                Text(response, 404, "Dati sconosciuti");
                return;
            }
            var file = Path.Combine(dataDir, name + ".json");
            if (request.HttpMethod == "PUT")
            {
                var text = Body(request);
                lock (dataLock) WriteAtomically(file, Encoding.UTF8.GetBytes(text));
                Text(response, 200, "ok");
                return;
            }
            string content;
            lock (dataLock) content = File.Exists(file) ? File.ReadAllText(file, Encoding.UTF8) : "null";
            Send(response, 200, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(content));
        }

        // YouTube's player script and its prepared version, kept between runs.
        static void Cache(HttpListenerRequest request, HttpListenerResponse response, string name)
        {
            if (request.HttpMethod == "DELETE" && name == "")
            {
                foreach (var file in Directory.GetFiles(cacheDir)) File.Delete(file);
                Text(response, 200, "ok");
                return;
            }
            if (!CacheName.IsMatch(name))
            {
                Text(response, 404, "Nome non valido");
                return;
            }
            var path = Path.Combine(cacheDir, name);
            if (request.HttpMethod == "PUT")
            {
                WriteAtomically(path, Encoding.UTF8.GetBytes(Body(request)));
                Text(response, 200, "ok");
            }
            else if (!File.Exists(path)) Text(response, 404, "Non in cache");
            else if (request.HttpMethod == "HEAD") Text(response, 200, "");
            else Send(response, 200, "text/javascript; charset=utf-8", File.ReadAllBytes(path));
        }

        // A request for the page, which gets the status in X-Status and the body as it came.
        // X-Status 0: no answer at all, and the body says why.
        static void Fetch(HttpListenerRequest request, HttpListenerResponse response)
        {
            var spec = Json().Deserialize<Dictionary<string, object>>(Body(request));
            var url = Get(spec, "url");
            if (!Allowed(url))
            {
                Text(response, 403, "Sito non permesso");
                return;
            }
            var body = Get(spec, "body");
            HttpWebResponse answer;
            try
            {
                answer = Request(url, Get(spec, "method") ?? "GET", spec.ContainsKey("headers") ? spec["headers"] as Dictionary<string, object> : null,
                    body == null ? null : Encoding.UTF8.GetBytes(body), true);
            }
            catch (WebException e)
            {
                response.AddHeader("X-Status", "0");
                Text(response, 200, e.Message);
                return;
            }
            using (answer)
            using (var stream = answer.GetResponseStream())
            {
                var bytes = ReadAll(stream);
                response.AddHeader("X-Status", ((int)answer.StatusCode).ToString());
                Send(response, 200, answer.ContentType ?? "text/plain", bytes);
            }
        }

        // JioSaavn hands out its stream links DES-encrypted, and a page has no DES.
        static void Des(HttpListenerRequest request, HttpListenerResponse response)
        {
            var input = Json().Deserialize<List<string>>(Body(request));
            var output = new List<string>();
            using (var des = new DESCryptoServiceProvider())
            {
                des.Key = Encoding.ASCII.GetBytes("38346591");
                des.Mode = CipherMode.ECB;
                des.Padding = PaddingMode.PKCS7;
                foreach (var text in input)
                {
                    try
                    {
                        var bytes = Convert.FromBase64String(text);
                        using (var decryptor = des.CreateDecryptor())
                        {
                            output.Add(Encoding.UTF8.GetString(decryptor.TransformFinalBlock(bytes, 0, bytes.Length)).Trim());
                        }
                    }
                    catch (Exception)
                    {
                        output.Add(null);
                    }
                }
            }
            Send(response, 200, "application/json", Encoding.UTF8.GetBytes(Json().Serialize(output)));
        }

        // Audio from JioSaavn or YouTube through here, so it's same-origin for the equalizer. The
        // player's Range goes along: with one, YouTube serves at full speed.
        static void Stream(HttpListenerRequest request, HttpListenerResponse response)
        {
            var url = request.QueryString["u"];
            if (!Allowed(url))
            {
                Text(response, 403, "Sito non permesso");
                return;
            }
            var headers = new Dictionary<string, object>();
            var range = request.Headers["Range"];
            headers["Range"] = range ?? "bytes=0-";
            HttpWebResponse answer;
            try
            {
                answer = Request(url, "GET", headers, null, false);
            }
            catch (WebException e)
            {
                Text(response, 502, e.Message);
                return;
            }
            using (answer)
            {
                response.StatusCode = (int)answer.StatusCode;
                response.ContentType = answer.ContentType;
                foreach (var name in new[] { "Content-Range", "Accept-Ranges" })
                {
                    if (answer.Headers[name] != null) response.AddHeader(name, answer.Headers[name]);
                }
                if (answer.ContentLength >= 0) response.ContentLength64 = answer.ContentLength;
                if (request.HttpMethod == "HEAD") return;
                using (var stream = answer.GetResponseStream()) stream.CopyTo(response.OutputStream, 65536);
            }
        }

        // Saves a song and its cover in music\, through .part files so a file is only ever whole.
        // DELETE removes both.
        static void Download(HttpListenerRequest request, HttpListenerResponse response)
        {
            if (request.HttpMethod == "DELETE")
            {
                var file = request.QueryString["name"];
                if (file == null || !SafeName.IsMatch(file))
                {
                    Text(response, 400, "Nome non valido");
                    return;
                }
                File.Delete(Path.Combine(musicDir, file + ".m4a"));
                File.Delete(Path.Combine(musicDir, file + ".jpg"));
                Text(response, 200, "ok");
                return;
            }
            var spec = Json().Deserialize<Dictionary<string, object>>(Body(request));
            var name = Get(spec, "name");
            var url = Get(spec, "url");
            var cover = Get(spec, "cover");
            if (name == null || !SafeName.IsMatch(name) || !Allowed(url))
            {
                Text(response, 400, "Richiesta non valida");
                return;
            }
            if (cover != null && Allowed(cover))
            {
                try { Save(cover, Path.Combine(musicDir, name + ".jpg"), false); }
                catch (Exception e) { Log("Copertina non scaricata: " + e.Message); }
            }
            try
            {
                long bytes = Save(url, Path.Combine(musicDir, name + ".m4a"), spec.ContainsKey("ranged") && true.Equals(spec["ranged"]));
                Send(response, 200, "application/json", Encoding.UTF8.GetBytes("{\"bytes\":" + bytes + "}"));
            }
            catch (Exception e)
            {
                Text(response, 502, e.Message);
            }
        }

        // YouTube throttles a request without a range to about twice the song's bitrate.
        static long Save(string url, string target, bool ranged)
        {
            var headers = new Dictionary<string, object>();
            if (ranged) headers["Range"] = "bytes=0-";
            var part = target + ".part";
            try
            {
                using (var answer = Request(url, "GET", headers, null, false))
                {
                    int status = (int)answer.StatusCode;
                    if (status != 200 && status != 206) throw new IOException("HTTP " + status);
                    long written;
                    using (var stream = answer.GetResponseStream())
                    using (var file = File.Create(part))
                    {
                        stream.CopyTo(file, 65536);
                        written = file.Length;
                    }
                    if (answer.ContentLength >= 0 && written != answer.ContentLength) throw new IOException("Download troncato");
                    if (File.Exists(target)) File.Delete(target);
                    File.Move(part, target);
                    return written;
                }
            }
            finally
            {
                if (File.Exists(part)) File.Delete(part);
            }
        }

        // Downloaded songs and covers, with ranges: the player seeks.
        static void Music(HttpListenerRequest request, HttpListenerResponse response, string name)
        {
            if (!MusicName.IsMatch(name) || !File.Exists(Path.Combine(musicDir, name)))
            {
                Text(response, 404, "Non trovato");
                return;
            }
            var path = Path.Combine(musicDir, name);
            long length = new FileInfo(path).Length, start = 0, end = length - 1;
            var range = Regex.Match(request.Headers["Range"] ?? "", @"^bytes=(\d*)-(\d*)$");
            if (range.Success && (range.Groups[1].Value != "" || range.Groups[2].Value != ""))
            {
                if (range.Groups[1].Value == "")
                {
                    start = Math.Max(0, length - long.Parse(range.Groups[2].Value));
                }
                else
                {
                    start = long.Parse(range.Groups[1].Value);
                    if (range.Groups[2].Value != "") end = Math.Min(end, long.Parse(range.Groups[2].Value));
                }
                if (start > end)
                {
                    response.AddHeader("Content-Range", "bytes */" + length);
                    Text(response, 416, "Intervallo non valido");
                    return;
                }
                response.StatusCode = 206;
                response.AddHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            }
            response.AddHeader("Accept-Ranges", "bytes");
            response.ContentType = name.EndsWith(".jpg") ? "image/jpeg" : "audio/mp4";
            if (name.EndsWith(".jpg")) response.AddHeader("Cache-Control", "max-age=86400");
            response.ContentLength64 = end - start + 1;
            if (request.HttpMethod == "HEAD") return;
            using (var file = File.OpenRead(path))
            {
                file.Seek(start, SeekOrigin.Begin);
                var buffer = new byte[65536];
                long left = end - start + 1;
                while (left > 0)
                {
                    int read = file.Read(buffer, 0, (int)Math.Min(buffer.Length, left));
                    if (read <= 0) break;
                    response.OutputStream.Write(buffer, 0, read);
                    left -= read;
                }
            }
        }

        // --- The phone, over USB ---------------------------------------------------------------

        // Everything the phone's app exports (see ExportProvider.kt), as JSON. Anything else is a
        // message for the user, with status 409.
        static void PhoneData(HttpListenerResponse response)
        {
            string error;
            var serial = FindPhone(out error);
            if (serial == null)
            {
                Text(response, 409, error);
                return;
            }
            string ignored;
            var installed = Encoding.UTF8.GetString(Adb(serial, "shell pm path " + Package, null, out ignored));
            if (!installed.Contains("package:"))
            {
                Text(response, 409, "Playerless non e' installata sul telefono.");
                return;
            }
            string stderr;
            var bytes = Adb(serial, "exec-out content read --uri content://" + Package + ".export/data", null, out stderr);
            var text = Encoding.UTF8.GetString(bytes).Trim();
            if (text.StartsWith("{"))
            {
                Send(response, 200, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(text));
                return;
            }
            Log("Esportazione dal telefono non riuscita: " + text + " " + stderr);
            if (Regex.IsMatch(text + stderr, "provider|authority", RegexOptions.IgnoreCase))
            {
                Text(response, 409, "Sul telefono c'e' una versione di Playerless che non sa esportare: installa quella nuova e riprova.");
            }
            else
            {
                Text(response, 409, "Il telefono non ha dato i dati: " + Shorten(text + " " + stderr));
            }
        }

        // One downloaded song or cover, straight from the phone's app into music\. The size from
        // the export tells a whole file from an error, which adb would put in the same stream.
        static void PhoneFile(HttpListenerRequest request, HttpListenerResponse response)
        {
            var spec = Json().Deserialize<Dictionary<string, object>>(Body(request));
            var name = Get(spec, "name");
            long expected;
            if (name == null || !MusicName.IsMatch(name) || !long.TryParse(Get(spec, "bytes"), out expected))
            {
                Text(response, 400, "Richiesta non valida");
                return;
            }
            var target = Path.Combine(musicDir, name);
            if (File.Exists(target) && new FileInfo(target).Length == expected)
            {
                Text(response, 200, "gia' presente");
                return;
            }
            string error = null;
            var serial = phone ?? FindPhone(out error);
            if (serial == null)
            {
                Text(response, 409, error);
                return;
            }
            var part = target + ".part";
            try
            {
                string stderr;
                using (var file = File.Create(part))
                {
                    Adb(serial, "exec-out content read --uri content://" + Package + ".export/music/" + name, file, out stderr);
                }
                if (new FileInfo(part).Length != expected)
                {
                    Text(response, 502, "Copia incompleta di " + name);
                    return;
                }
                if (File.Exists(target)) File.Delete(target);
                File.Move(part, target);
                Text(response, 200, "ok");
            }
            finally
            {
                if (File.Exists(part)) File.Delete(part);
            }
        }

        static string FindPhone(out string error)
        {
            phone = null;
            var adb = FindAdb();
            if (adb == null)
            {
                error = "Non trovo adb: installa gli Android SDK Platform Tools (o Android Studio).";
                return null;
            }
            string stderr;
            var lines = Encoding.UTF8.GetString(Adb(null, "devices", null, out stderr)).Split('\n');
            var ready = new List<string>();
            bool unauthorized = false;
            foreach (var line in lines)
            {
                var parts = line.Trim().Split('\t');
                if (parts.Length != 2) continue;
                if (parts[1] == "device") ready.Add(parts[0]);
                else if (parts[1] == "unauthorized") unauthorized = true;
            }
            if (chosenPhone != null && ready.Contains(chosenPhone)) ready = new List<string> { chosenPhone };
            if (ready.Count == 1)
            {
                error = null;
                phone = ready[0];
                return phone;
            }
            if (ready.Count > 1) error = "Sono collegati piu' telefoni: lasciane collegato uno solo (" + string.Join(", ", ready) + ").";
            else if (unauthorized) error = "Sblocca il telefono e accetta la richiesta di debug USB, poi riprova.";
            else error = "Telefono non trovato: collegalo con il cavo USB, attiva il debug USB e sbloccalo.";
            return null;
        }

        static string FindAdb()
        {
            var candidates = new List<string>();
            candidates.Add(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe"));
            foreach (var variable in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
            {
                var sdk = Environment.GetEnvironmentVariable(variable);
                if (!string.IsNullOrEmpty(sdk)) candidates.Add(Path.Combine(sdk, @"platform-tools\adb.exe"));
            }
            candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Android\Sdk\platform-tools\adb.exe"));
            foreach (var folder in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            {
                if (folder.Trim() != "") candidates.Add(Path.Combine(folder.Trim().Trim('"'), "adb.exe"));
            }
            foreach (var path in candidates)
            {
                try { if (File.Exists(path)) return path; } catch (ArgumentException) { }
            }
            return null;
        }

        // Runs adb; stdout goes to output when given, else comes back.
        static byte[] Adb(string serial, string arguments, Stream output, out string stderr)
        {
            var start = new ProcessStartInfo(FindAdb(), (serial != null ? "-s " + serial + " " : "") + arguments);
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.RedirectStandardOutput = true;
            start.RedirectStandardError = true;
            using (var process = Process.Start(start))
            {
                string errors = "";
                var reader = new Thread(() => { errors = process.StandardError.ReadToEnd(); });
                reader.Start();
                var buffer = output == null ? new MemoryStream() : null;
                process.StandardOutput.BaseStream.CopyTo(output ?? buffer, 65536);
                process.WaitForExit();
                reader.Join();
                stderr = errors;
                return buffer == null ? new byte[0] : buffer.ToArray();
            }
        }

        // --- Helpers ---------------------------------------------------------------------------

        static void Static(HttpListenerResponse response, string name)
        {
            byte[] bytes = null;
            if (StaticName.IsMatch(name) && !name.Contains(".."))
            {
                // --web serves the page from a folder, for editing without rebuilding.
                if (webDir != null)
                {
                    var path = Path.Combine(webDir, name);
                    if (File.Exists(path)) bytes = File.ReadAllBytes(path);
                }
                if (bytes == null)
                {
                    using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("web/" + name))
                    {
                        if (stream != null) bytes = ReadAll(stream);
                    }
                }
            }
            if (bytes == null)
            {
                Text(response, 404, "Non trovato");
                return;
            }
            response.AddHeader("Cache-Control", "no-cache");
            Send(response, 200, Mime(name), bytes);
        }

        static string Mime(string name)
        {
            switch (Path.GetExtension(name))
            {
                case ".html": return "text/html; charset=utf-8";
                case ".js": return "text/javascript; charset=utf-8";
                case ".css": return "text/css; charset=utf-8";
                case ".svg": return "image/svg+xml";
                case ".png": return "image/png";
                case ".ico": return "image/x-icon";
                default: return "application/octet-stream";
            }
        }

        static bool Allowed(string url)
        {
            Uri uri;
            if (url == null || !Uri.TryCreate(url, UriKind.Absolute, out uri) || uri.Scheme != "https") return false;
            foreach (var host in Hosts)
            {
                if (uri.Host == host || uri.Host.EndsWith("." + host)) return true;
            }
            return false;
        }

        static HttpWebResponse Request(string url, string method, Dictionary<string, object> headers, byte[] body, bool decompress)
        {
            var request = (HttpWebRequest)WebRequest.Create(url);
            request.Method = method;
            request.Timeout = 15000;
            request.ReadWriteTimeout = 30000;
            request.UserAgent = WebUA;
            if (decompress) request.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            if (headers != null)
            {
                foreach (var header in headers) SetHeader(request, header.Key, Convert.ToString(header.Value));
            }
            if (body != null)
            {
                request.ContentLength = body.Length;
                using (var stream = request.GetRequestStream()) stream.Write(body, 0, body.Length);
            }
            try
            {
                return (HttpWebResponse)request.GetResponse();
            }
            catch (WebException e)
            {
                // An error status is still an answer, for the page to read.
                var answer = e.Response as HttpWebResponse;
                if (answer != null) return answer;
                throw;
            }
        }

        // HttpWebRequest wants some headers set through properties.
        static void SetHeader(HttpWebRequest request, string name, string value)
        {
            switch (name.ToLowerInvariant())
            {
                case "user-agent": request.UserAgent = value; break;
                case "referer": request.Referer = value; break;
                case "content-type": request.ContentType = value; break;
                case "accept": request.Accept = value; break;
                case "range":
                    var range = Regex.Match(value, @"^bytes=(\d+)-(\d*)$");
                    if (!range.Success) break;
                    if (range.Groups[2].Value == "") request.AddRange(long.Parse(range.Groups[1].Value));
                    else request.AddRange(long.Parse(range.Groups[1].Value), long.Parse(range.Groups[2].Value));
                    break;
                case "host": case "connection": case "content-length": case "expect": case "transfer-encoding": break;
                default: request.Headers[name] = value; break;
            }
        }

        static void WriteAtomically(string path, byte[] bytes)
        {
            var temp = path + ".tmp";
            File.WriteAllBytes(temp, bytes);
            if (File.Exists(path)) File.Replace(temp, path, null);
            else File.Move(temp, path);
        }

        static string Get(Dictionary<string, object> map, string key)
        {
            object value;
            return map != null && map.TryGetValue(key, out value) && value != null ? Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture) : null;
        }

        static string Body(HttpListenerRequest request)
        {
            using (var reader = new StreamReader(request.InputStream, Encoding.UTF8)) return reader.ReadToEnd();
        }

        static byte[] ReadAll(Stream stream)
        {
            using (var memory = new MemoryStream())
            {
                stream.CopyTo(memory);
                return memory.ToArray();
            }
        }

        static void Send(HttpListenerResponse response, int status, string type, byte[] body)
        {
            response.StatusCode = status;
            response.ContentType = type;
            response.ContentLength64 = body.Length;
            response.OutputStream.Write(body, 0, body.Length);
        }

        static void Text(HttpListenerResponse response, int status, string text)
        {
            Send(response, status, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes(text));
        }

        static string Shorten(string text)
        {
            text = Regex.Replace(text, @"\s+", " ").Trim();
            return text.Length > 300 ? text.Substring(0, 300) + "..." : text;
        }

        // Asks the Playerless already running, if any.
        static string Call(string path)
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create(Origin + path);
                request.Timeout = 3000;
                using (var answer = request.GetResponse())
                using (var reader = new StreamReader(answer.GetResponseStream())) return reader.ReadToEnd();
            }
            catch (Exception)
            {
                return null;
            }
        }

        static void Log(string text)
        {
            lock (logLock)
            {
                try { File.AppendAllText(logFile, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + text + "\r\n"); }
                catch (IOException) { }
            }
        }

        static void Fail(string message)
        {
            Log(message);
            if (!server) MessageBox.Show(message, "Playerless", MessageBoxButtons.OK, MessageBoxIcon.Error);
            else Console.Error.WriteLine(message);
        }
    }
}
