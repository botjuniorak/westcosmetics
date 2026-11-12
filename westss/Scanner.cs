using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Management;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace WestSS
{
    /// <summary>Jedno znalezisko. Poziom: "cheat" (pewny cheat), "podejrzane", "info".</summary>
    sealed class Finding
    {
        public string Level, Category, Text, Path, When;
    }

    /// <summary>
    /// Szuka sladow cheatow: mody i wersje Minecrafta (wszystkie popularne launchery), uruchamiane programy (Prefetch, BAM),
    /// dzialajace procesy i agenty Javy, kosz, ostatnio otwierane pliki i Pobrane.
    /// Nie czyta tresci dokumentow ani zdjec - patrzy tylko na nazwy plikow, mody i liste procesow.
    /// </summary>
    sealed class Scanner
    {
        public readonly List<Finding> Findings = new List<Finding>();
        public readonly List<string> Mods = new List<string>();
        public readonly List<string> Dirs = new List<string>();
        public bool Admin;
        public int UptimeMinutes = -1;
        public int ScannedFiles;
        public Action<string, int> Progress = (s, p) => { };

        static readonly string Home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        static readonly string AppData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        static readonly string Local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);

        // nazwy (cale slowa w nazwie pliku / procesu)
        static readonly HashSet<string> CheatWords = new HashSet<string>(StringComparer.OrdinalIgnoreCase) {
            "meteor", "meteorclient", "wurst", "wurstclient", "liquidbounce", "aristois", "thunderhack", "nursultan", "wexside",
            "bleachhack", "rusherhack", "doomsday", "vape", "vapev4", "vapelite", "killaura", "triggerbot", "aimbot", "aimassist",
            "autoclicker", "autoclick", "xray", "xrayultimate", "advancedxray", "konas", "boze", "futureclient",
            "impactclient", "novoline", "kamiblue", "salhack", "prestigeclient", "argonclient", "celestialclient", "expensiveclient", "akrien",
            "dllinjector", "extremeinjector", "xenos", "opclicker", "gsclicker", "jitterclick"
        };
        static readonly HashSet<string> SuspiciousWords = new HashSet<string>(StringComparer.OrdinalIgnoreCase) {
            "baritone", "freecam", "autototem", "autocrystal", "crystalaura", "inventorywalk", "macro", "macros", "cheat", "cheats",
            "hack", "hacks", "hacked", "hacker", "bypass", "ghostclient", "esp", "reach", "cheatengine", "processhacker",
            "systeminformer", "inject", "injector", "clicker", "inertia", "sigma", "tenacity", "phobos", "wolfram", "breeze", "slinky", "drip", "entropy", "koid", "whiteout", "itami", "deadcode", "jigsaw", "zeroday", "selfdestruct", "destruct", "cleaner", "shellbag", "everything", "lastactivityview"
        };
        // pakiety klas wewnatrz modow (najpewniejszy slad)
        static readonly string[] CheatPackages = {
            "meteordevelopment/meteorclient/", "net/wurstclient/", "net/ccbluex/liquidbounce/", "me/deftware/", "org/bleachhack/",
            "dev/thunderhack/", "thunderhack/", "org/rusherhack/", "dev/boze/", "com/konasclient/", "cookiedragon/", "me/zeroeightsix/kami/",
            "com/lambda/client/", "net/impactclient/", "rip/vape/", "xyz/doomsday/"
        };
        static readonly string[] SuspiciousPackages = { "baritone/", "net/xolt/freecam/", "com/xray/", "xray/" };
        static readonly string[] CheatModIds = { "meteor-client", "wurst", "liquidbounce", "aristois", "bleachhack", "thunderhack", "rusherhack", "boze", "xray", "advanced-xray", "xrayultimate", "autoclicker", "killaura", "triggerbot", "aimassist" };
        static readonly string[] SuspiciousModIds = { "baritone", "freecam", "autototem", "inventorywalk", "tweakeroo", "itemscroller" };

        static string Short(string p)
        {
            if (p == null) return "";
            if (!string.IsNullOrEmpty(Home) && p.StartsWith(Home, StringComparison.OrdinalIgnoreCase)) return "%USERPROFILE%" + p.Substring(Home.Length);
            return p;
        }

        static IEnumerable<string> Words(string name)
        {
            string n = name.ToLowerInvariant();
            foreach (string w in Regex.Split(n, "[^a-z0-9]+")) if (w.Length > 1) yield return w;
            // tez sklejone, np. "Vape-Lite" -> "vapelite"
            yield return Regex.Replace(n, "[^a-z0-9]", "");
            string noExt = Path.GetFileNameWithoutExtension(n);
            yield return Regex.Replace(noExt, "[^a-z0-9]", "");
        }

        /// <summary>"cheat", "podejrzane" albo null.</summary>
        static string Classify(string name)
        {
            string best = null;
            foreach (string w in Words(name))
            {
                if (CheatWords.Contains(w)) return "cheat";
                if (SuspiciousWords.Contains(w)) best = "podejrzane";
            }
            return best;
        }

        void Add(string level, string cat, string text, string path = null, DateTime? when = null)
        {
            lock (Findings)
            {
                string sp = Short(path);
                if (Findings.Any(f => f.Category == cat && f.Path == sp && f.Text == text)) return;
                Findings.Add(new Finding { Level = level, Category = cat, Text = text, Path = sp, When = when.HasValue ? when.Value.ToString("yyyy-MM-dd HH:mm") : null });
            }
        }

        static void Safe(Action a) { try { a(); } catch { } }

        public void Run()
        {
            Admin = new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
            Progress("Informacje o systemie", 3); Safe(SystemInfo);
            Progress("Mody i wersje Minecrafta", 10); Safe(Minecraft);
            Progress("Działające programy", 45); Safe(Processes);
            Progress("Uruchamiane programy (Prefetch)", 60); Safe(Prefetch);
            Progress("Historia uruchomień (BAM)", 70); Safe(Bam);
            Progress("Kosz", 78); Safe(RecycleBin);
            Progress("Ostatnio otwierane pliki", 85); Safe(Recent);
            Progress("Pobrane, pulpit, pliki tymczasowe", 92); Safe(Downloads);
            Progress("Gotowe", 100);
        }

        // ------------------------------------------------------------ system

        void SystemInfo()
        {
            using (var s = new ManagementObjectSearcher("SELECT LastBootUpTime FROM Win32_OperatingSystem"))
                foreach (ManagementObject o in s.Get())
                {
                    DateTime boot = ManagementDateTimeConverter.ToDateTime(o["LastBootUpTime"].ToString());
                    UptimeMinutes = (int)(DateTime.Now - boot).TotalMinutes;
                }
            if (UptimeMinutes >= 0 && UptimeMinutes < 15)
                Add("podejrzane", "System", "Komputer był uruchomiony dopiero " + UptimeMinutes + " min temu (restart czyści część śladów).");
            if (!Admin) Add("info", "System", "Bez uprawnień administratora: Prefetch i BAM mogą być niedostępne.");
        }

        // ------------------------------------------------------------ minecraft

        IEnumerable<string> GameRoots()
        {
            var roots = new List<string> {
                Path.Combine(AppData, ".minecraft"), Path.Combine(AppData, ".tlauncher"), Path.Combine(Home, ".lunarclient"),
                Path.Combine(AppData, ".feather"), Path.Combine(AppData, ".badlion"), Path.Combine(AppData, "Badlion Client"),
                Path.Combine(AppData, "PrismLauncher", "instances"), Path.Combine(AppData, "MultiMC", "instances"),
                Path.Combine(AppData, "com.modrinth.theseus", "profiles"), Path.Combine(AppData, "ModrinthApp", "profiles"),
                Path.Combine(Home, "curseforge", "minecraft", "Instances"), Path.Combine(AppData, "gdlauncher_next"),
                Path.Combine(AppData, ".atlauncher"), Path.Combine(AppData, "ATLauncher", "instances"), Path.Combine(AppData, ".sklauncher"),
                Path.Combine(AppData, ".blazingpack"), Path.Combine(AppData, ".atomic"), Path.Combine(AppData, "atomic-launcher"),
                Path.Combine(Local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Roaming", ".minecraft")
            };
            return roots.Where(Directory.Exists);
        }

        void Minecraft()
        {
            var jars = new List<string>();
            foreach (string root in GameRoots())
            {
                Dirs.Add(Short(root));
                Safe(() =>
                {
                    foreach (string f in Directory.EnumerateFiles(root, "*.jar", SearchOption.AllDirectories))
                    {
                        string lower = f.ToLowerInvariant();
                        // biblioteki i natywne pliki gry pomijamy - tam nie ma cheatow, a jest ich tysiace
                        if (lower.Contains("\\libraries\\") || lower.Contains("\\assets\\") || lower.Contains("\\runtime\\") || lower.Contains("\\jre\\")) continue;
                        jars.Add(f);
                    }
                });
                // nazwy folderow wersji (np. wlasne wersje z cheatem)
                Safe(() =>
                {
                    string versions = Path.Combine(root, "versions");
                    if (Directory.Exists(versions))
                        foreach (string d in Directory.GetDirectories(versions))
                        {
                            string c = Classify(Path.GetFileName(d));
                            if (c != null) Add(c, "Wersje Minecrafta", "Wersja gry o nazwie: " + Path.GetFileName(d), d, Directory.GetLastWriteTime(d));
                        }
                });
            }
            int i = 0;
            foreach (string jar in jars.Distinct(StringComparer.OrdinalIgnoreCase))
            {
                i++;
                if (i % 10 == 0) Progress("Mody i wersje Minecrafta (" + i + "/" + jars.Count + ")", 10 + 35 * i / Math.Max(1, jars.Count));
                Safe(() => InspectJar(jar));
            }
        }

        void InspectJar(string jar)
        {
            ScannedFiles++;
            var info = new FileInfo(jar);
            string name = info.Name;
            bool inMods = jar.ToLowerInvariant().Contains("\\mods\\");
            string byName = Classify(name);
            string modId = null, modName = null, level = byName, why = byName != null ? "nazwa pliku" : null;
            if (info.Length > 200L * 1024 * 1024) return;
            using (ZipArchive z = ZipFile.OpenRead(jar))
            {
                foreach (ZipArchiveEntry e in z.Entries)
                {
                    string n = e.FullName;
                    if (n == "fabric.mod.json" || n == "quilt.mod.json")
                    {
                        using (var r = new StreamReader(e.Open(), Encoding.UTF8))
                        {
                            string json = r.ReadToEnd();
                            modId = Match(json, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                            modName = Match(json, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                        }
                    }
                    else if (n == "META-INF/mods.toml" || n == "META-INF/neoforge.mods.toml")
                    {
                        using (var r = new StreamReader(e.Open(), Encoding.UTF8))
                        {
                            string t = r.ReadToEnd();
                            modId = Match(t, "modId\\s*=\\s*\"([^\"]+)\"");
                            modName = Match(t, "displayName\\s*=\\s*\"([^\"]+)\"");
                        }
                    }
                    if (level != "cheat")
                    {
                        foreach (string p in CheatPackages) if (n.StartsWith(p, StringComparison.Ordinal)) { level = "cheat"; why = "kod cheata w środku (" + p.TrimEnd('/') + ")"; break; }
                        if (level == null) foreach (string p in SuspiciousPackages) if (n.StartsWith(p, StringComparison.Ordinal)) { level = "podejrzane"; why = "podejrzany kod (" + p.TrimEnd('/') + ")"; break; }
                    }
                }
            }
            if (modId != null)
            {
                string id = modId.ToLowerInvariant();
                if (CheatModIds.Any(x => id == x || id.Contains(x))) { level = "cheat"; why = "mod " + modId; }
                else if (level == null && SuspiciousModIds.Contains(id)) { level = "podejrzane"; why = "mod " + modId; }
                else if (level == null) { string c = Classify(modId + " " + (modName ?? "")); if (c != null) { level = c; why = "nazwa moda " + (modName ?? modId); } }
            }
            if (inMods) lock (Mods) Mods.Add((modName ?? Path.GetFileNameWithoutExtension(name)) + (modId != null ? " [" + modId + "]" : ""));
            if (level != null) Add(level, "Minecraft", (modName ?? name) + " — " + why, jar, info.LastWriteTime);
        }

        static string Match(string s, string re) { var m = Regex.Match(s, re); return m.Success ? m.Groups[1].Value : null; }

        // ------------------------------------------------------------ procesy

        void Processes()
        {
            foreach (Process p in Process.GetProcesses())
            {
                Safe(() =>
                {
                    string c = Classify(p.ProcessName);
                    string title = "";
                    Safe(() => title = p.MainWindowTitle);
                    if (c == null && !string.IsNullOrEmpty(title)) c = Classify(title);
                    if (c != null) Add(c, "Działające programy", p.ProcessName + (string.IsNullOrEmpty(title) ? "" : " („" + title + "”)"));
                });
            }
            // Java: agenty wstrzykniete do gry (-javaagent) to typowy sposob ladowania cheatow
            using (var s = new ManagementObjectSearcher("SELECT Name, CommandLine FROM Win32_Process WHERE Name LIKE 'java%'"))
                foreach (ManagementObject o in s.Get())
                {
                    string cmd = o["CommandLine"] as string;
                    if (string.IsNullOrEmpty(cmd)) continue;
                    foreach (Match m in Regex.Matches(cmd, "-(javaagent|agentpath|agentlib):(\"[^\"]+\"|\\S+)", RegexOptions.IgnoreCase))
                    {
                        string path = m.Groups[2].Value.Trim('"');
                        string c = Classify(Path.GetFileName(path.Split('=')[0]));
                        Add(c ?? "podejrzane", "Działające programy", "Gra uruchomiona z dodatkiem (" + m.Groups[1].Value + "): " + Path.GetFileName(path.Split('=')[0]), path);
                    }
                }
        }

        // ------------------------------------------------------------ prefetch / bam

        void Prefetch()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Prefetch");
            string[] files;
            try { files = Directory.GetFiles(dir, "*.pf"); }
            catch { Add("info", "Prefetch", "Brak dostępu do Prefetch (uruchom jako administrator)."); return; }
            if (files.Length < 15) Add("podejrzane", "Prefetch", "Prefetch jest prawie pusty (" + files.Length + " plików), mógł zostać wyczyszczony.");
            foreach (string f in files)
            {
                ScannedFiles++;
                string exe = Path.GetFileNameWithoutExtension(f);
                int dash = exe.LastIndexOf('-');
                if (dash > 0) exe = exe.Substring(0, dash);
                string c = Classify(exe);
                if (c != null) Add(c, "Uruchamiane programy", "Uruchamiano: " + exe, null, File.GetLastWriteTime(f));
            }
        }

        void Bam()
        {
            string sid = WindowsIdentity.GetCurrent().User?.Value;
            foreach (string root in new[] { @"SYSTEM\CurrentControlSet\Services\bam\State\UserSettings\", @"SYSTEM\CurrentControlSet\Services\bam\UserSettings\" })
            {
                using (RegistryKey k = Registry.LocalMachine.OpenSubKey(root + sid))
                {
                    if (k == null) continue;
                    foreach (string v in k.GetValueNames())
                    {
                        string c = Classify(Path.GetFileName(v));
                        if (c == null) continue;
                        DateTime? when = null;
                        if (k.GetValue(v) is byte[] b && b.Length >= 8) Safe(() => when = DateTime.FromFileTime(BitConverter.ToInt64(b, 0)));
                        Add(c, "Uruchamiane programy", "Uruchamiano: " + Path.GetFileName(v), v.Contains(":\\") ? v.Substring(v.IndexOf(":\\") - 1) : v, when);
                    }
                }
            }
        }

        // ------------------------------------------------------------ kosz, ostatnie, pobrane

        void RecycleBin()
        {
            string sid = WindowsIdentity.GetCurrent().User?.Value;
            if (sid == null) return;
            foreach (DriveInfo d in DriveInfo.GetDrives().Where(x => x.DriveType == DriveType.Fixed && x.IsReady))
            {
                string bin = Path.Combine(d.RootDirectory.FullName, "$Recycle.Bin", sid);
                if (!Directory.Exists(bin)) continue;
                foreach (string f in Directory.GetFiles(bin, "$I*"))
                {
                    Safe(() =>
                    {
                        byte[] b = File.ReadAllBytes(f);
                        if (b.Length < 28) return;
                        DateTime deleted = DateTime.FromFileTime(BitConverter.ToInt64(b, 16));
                        string original = BitConverter.ToInt64(b, 0) >= 2
                            ? Encoding.Unicode.GetString(b, 28, Math.Min(b.Length - 28, BitConverter.ToInt32(b, 24) * 2)).TrimEnd('\0')
                            : Encoding.Unicode.GetString(b, 24, b.Length - 24).TrimEnd('\0');
                        string name = Path.GetFileName(original);
                        string ext = Path.GetExtension(name).ToLowerInvariant();
                        string c = Classify(name);
                        bool recentJar = (ext == ".jar" || ext == ".exe" || ext == ".dll") && (DateTime.Now - deleted).TotalHours < 48;
                        if (c != null) Add(c, "Kosz", "Usunięty plik: " + name, original, deleted);
                        else if (recentJar) Add("podejrzane", "Kosz", "Niedawno usunięty program/mod: " + name, original, deleted);
                    });
                }
            }
        }

        void Recent()
        {
            string dir = Path.Combine(AppData, "Microsoft", "Windows", "Recent");
            if (!Directory.Exists(dir)) return;
            string[] files = Directory.GetFiles(dir, "*.lnk");
            if (files.Length < 5) Add("podejrzane", "Ostatnie pliki", "Lista ostatnio otwieranych plików jest prawie pusta, mogła zostać wyczyszczona.");
            foreach (string f in files)
            {
                ScannedFiles++;
                string name = Path.GetFileNameWithoutExtension(f);
                string c = Classify(name);
                if (c != null) Add(c, "Ostatnie pliki", "Otwierano: " + name, null, File.GetLastWriteTime(f));
            }
        }

        void Downloads()
        {
            var dirs = new[] { Path.Combine(Home, "Downloads"), Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Path.GetTempPath(), Path.Combine(Home, "Documents") };
            foreach (string dir in dirs.Where(Directory.Exists))
            {
                Safe(() =>
                {
                    foreach (string f in EnumerateShallow(dir, 2))
                    {
                        ScannedFiles++;
                        string ext = Path.GetExtension(f).ToLowerInvariant();
                        if (ext != ".jar" && ext != ".exe" && ext != ".dll" && ext != ".zip" && ext != ".rar" && ext != ".7z" && ext != ".bat" && ext != ".ahk") continue;
                        string c = Classify(Path.GetFileName(f));
                        if (c != null) Add(c, "Pliki na dysku", Path.GetFileName(f), f, File.GetLastWriteTime(f));
                        else if (ext == ".jar" && f.ToLowerInvariant().Contains("downloads")) Safe(() => InspectJar(f));
                    }
                });
            }
        }

        static IEnumerable<string> EnumerateShallow(string dir, int depth)
        {
            string[] files = new string[0], subs = new string[0];
            try { files = Directory.GetFiles(dir); } catch { }
            foreach (string f in files) yield return f;
            if (depth <= 1) yield break;
            try { subs = Directory.GetDirectories(dir); } catch { }
            foreach (string s in subs)
                foreach (string f in EnumerateShallow(s, depth - 1)) yield return f;
        }

        // ------------------------------------------------------------ raport

        public string ToJson(string code)
        {
            var sb = new StringBuilder();
            sb.Append("{\"code\":").Append(Q(code)).Append(",\"version\":\"1.0.0\",\"admin\":").Append(Admin ? "true" : "false");
            sb.Append(",\"uptime\":").Append(UptimeMinutes).Append(",\"scanned\":").Append(ScannedFiles);
            sb.Append(",\"os\":").Append(Q(Environment.OSVersion.VersionString));
            sb.Append(",\"findings\":[");
            bool first = true;
            foreach (Finding f in Findings.OrderBy(x => x.Level == "cheat" ? 0 : x.Level == "podejrzane" ? 1 : 2).Take(400))
            {
                if (!first) sb.Append(',');
                first = false;
                sb.Append("{\"level\":").Append(Q(f.Level)).Append(",\"cat\":").Append(Q(f.Category)).Append(",\"text\":").Append(Q(f.Text))
                  .Append(",\"path\":").Append(Q(f.Path)).Append(",\"when\":").Append(Q(f.When)).Append('}');
            }
            sb.Append("],\"mods\":[").Append(string.Join(",", Mods.Distinct().Take(300).Select(Q))).Append(']');
            sb.Append(",\"dirs\":[").Append(string.Join(",", Dirs.Select(Q))).Append("]}");
            return sb.ToString();
        }

        public static string Q(string s)
        {
            if (s == null) return "null";
            var sb = new StringBuilder("\"");
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default: if (c < 32) sb.Append("\\u").Append(((int)c).ToString("x4")); else sb.Append(c); break;
                }
            }
            return sb.Append('"').ToString();
        }
    }
}
