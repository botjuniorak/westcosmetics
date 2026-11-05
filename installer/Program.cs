using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace WestMC
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }
    }

    /// <summary>
    /// Instalator WestMC: dodaje do oficjalnego launchera Minecrafta profil "WestMC"
    /// (Fabric 1.21.4 + Fabric API + WestCosmetics, opcjonalnie Sodium + Iris) w osobnym folderze gry.
    /// Nic nie podmienia w systemie i nie wymaga uprawnien administratora.
    /// </summary>
    internal sealed class InstallerForm : Form
    {
        private const string McVersion = "1.21.4";
        private const string ProfileId = "westmc";
        private const string ProfileName = "WestMC";
        private const string CosmeticsRepo = "botjuniorak/westcosmetics";
        private const string ServerIp = "westmc.eu";

        private static readonly Color Bg = Color.FromArgb(10, 16, 34);
        private static readonly Color Panel = Color.FromArgb(18, 28, 56);
        private static readonly Color Accent = Color.FromArgb(47, 140, 255);
        private static readonly Color Gold = Color.FromArgb(255, 194, 61);
        private static readonly Color Muted = Color.FromArgb(150, 170, 210);

        private readonly CheckBox chkPerformance;
        private readonly ComboBox cmbRam;
        private readonly TextBox txtMcDir;
        private readonly ProgressBar progress;
        private readonly Label lblStatus;
        private readonly Button btnInstall;
        private readonly HttpClient http;

        public InstallerForm()
        {
            http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("WestMC-Instalator/1.0 (westmc.eu)");

            Text = "WestMC - Instalator";
            ClientSize = new Size(520, 470);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10F);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var logo = new PictureBox
            {
                Size = new Size(72, 72),
                Location = new Point(30, 26),
                SizeMode = PictureBoxSizeMode.Zoom,
                BackColor = Color.Transparent
            };
            using (var s = typeof(InstallerForm).Assembly.GetManifestResourceStream("logo.png"))
                if (s != null) logo.Image = Image.FromStream(s);
            Controls.Add(logo);

            Controls.Add(new Label
            {
                Text = "WestMC",
                Font = new Font("Segoe UI", 26F, FontStyle.Bold),
                ForeColor = Color.White,
                AutoSize = true,
                Location = new Point(112, 22)
            });
            Controls.Add(new Label
            {
                Text = "Klient z kosmetykami WestMC  •  Minecraft " + McVersion,
                ForeColor = Muted,
                AutoSize = true,
                Location = new Point(116, 70)
            });

            var box = new Panel { Location = new Point(30, 120), Size = new Size(460, 200), BackColor = Panel };
            Controls.Add(box);

            box.Controls.Add(new Label
            {
                Text = "Folder Minecrafta",
                ForeColor = Muted,
                AutoSize = true,
                Location = new Point(16, 14)
            });
            txtMcDir = new TextBox
            {
                Location = new Point(16, 38),
                Size = new Size(338, 26),
                BackColor = Bg,
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
                Text = DefaultMinecraftDir()
            };
            box.Controls.Add(txtMcDir);
            var btnBrowse = FlatButton("Zmień", Panel, 90, 28);
            btnBrowse.Location = new Point(360, 37);
            btnBrowse.FlatAppearance.BorderColor = Accent;
            btnBrowse.Click += (o, e) =>
            {
                using (var d = new FolderBrowserDialog { SelectedPath = txtMcDir.Text })
                    if (d.ShowDialog(this) == DialogResult.OK) txtMcDir.Text = d.SelectedPath;
            };
            box.Controls.Add(btnBrowse);

            box.Controls.Add(new Label
            {
                Text = "Pamięć RAM dla gry",
                ForeColor = Muted,
                AutoSize = true,
                Location = new Point(16, 80)
            });
            cmbRam = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(16, 104),
                Size = new Size(140, 26),
                BackColor = Bg,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            cmbRam.Items.AddRange(new object[] { "2 GB", "3 GB", "4 GB", "6 GB", "8 GB" });
            cmbRam.SelectedIndex = 2;
            box.Controls.Add(cmbRam);

            chkPerformance = new CheckBox
            {
                Text = "Więcej FPS i shadery (Sodium + Iris)",
                Checked = true,
                AutoSize = true,
                ForeColor = Color.White,
                Location = new Point(16, 148)
            };
            box.Controls.Add(chkPerformance);

            progress = new ProgressBar { Location = new Point(30, 340), Size = new Size(460, 10), Style = ProgressBarStyle.Continuous };
            Controls.Add(progress);
            lblStatus = new Label
            {
                Text = "Gotowy do instalacji.",
                ForeColor = Muted,
                AutoSize = false,
                Size = new Size(460, 40),
                Location = new Point(30, 356)
            };
            Controls.Add(lblStatus);

            btnInstall = FlatButton("ZAINSTALUJ", Accent, 460, 46);
            btnInstall.Location = new Point(30, 402);
            btnInstall.Font = new Font("Segoe UI", 12F, FontStyle.Bold);
            btnInstall.Click += async (o, e) => await InstallAsync();
            Controls.Add(btnInstall);
        }

        private static Button FlatButton(string text, Color back, int w, int h)
        {
            var b = new Button
            {
                Text = text,
                Size = new Size(w, h),
                FlatStyle = FlatStyle.Flat,
                BackColor = back,
                ForeColor = Color.White,
                Cursor = Cursors.Hand
            };
            b.FlatAppearance.BorderSize = 1;
            b.FlatAppearance.BorderColor = back;
            return b;
        }

        private static string DefaultMinecraftDir()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft");
        }

        private void Status(string text, int percent)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => Status(text, percent))); return; }
            lblStatus.Text = text;
            progress.Value = Math.Max(0, Math.Min(100, percent));
        }

        private async Task InstallAsync()
        {
            string mcDir = txtMcDir.Text.Trim();
            if (!Directory.Exists(mcDir) ||
                (!File.Exists(Path.Combine(mcDir, "launcher_profiles.json")) &&
                 !File.Exists(Path.Combine(mcDir, "launcher_profiles_microsoft_store.json"))))
            {
                MessageBox.Show(this,
                    "Nie znaleziono launchera Minecrafta w tym folderze.\n\n" +
                    "Uruchom najpierw oficjalny launcher Minecrafta przynajmniej raz, a potem odpal instalator ponownie.",
                    "WestMC", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            if (System.Diagnostics.Process.GetProcessesByName("MinecraftLauncher").Length > 0)
            {
                MessageBox.Show(this, "Zamknij launcher Minecrafta przed instalacją, a potem kliknij ZAINSTALUJ jeszcze raz.",
                    "WestMC", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            btnInstall.Enabled = false;
            btnInstall.Text = "INSTALUJĘ...";
            bool perf = chkPerformance.Checked;
            int ramGb = int.Parse(cmbRam.SelectedItem.ToString().Split(' ')[0]);
            try
            {
                await Task.Run(() => DoInstall(mcDir, perf, ramGb));
                Status("Gotowe! Otwórz launcher Minecrafta i wybierz profil WestMC.", 100);
                btnInstall.Text = "ZAINSTALOWANO ✔";
                btnInstall.BackColor = Color.FromArgb(40, 160, 90);
                MessageBox.Show(this,
                    "WestMC zostało zainstalowane!\n\n" +
                    "1. Otwórz oficjalny launcher Minecrafta.\n" +
                    "2. Obok przycisku GRAJ wybierz profil \"WestMC\".\n" +
                    "3. Kliknij GRAJ.\n\nSerwer westmc.eu jest już na liście serwerów. Miłej gry!",
                    "WestMC", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                Status("Błąd: " + ex.Message, 0);
                btnInstall.Enabled = true;
                btnInstall.Text = "SPRÓBUJ PONOWNIE";
                MessageBox.Show(this, "Instalacja nie powiodła się:\n\n" + ex.Message +
                    "\n\nSprawdź połączenie z internetem i spróbuj ponownie.", "WestMC",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ------------------------------------------------------------ instalacja

        private void DoInstall(string mcDir, bool perf, int ramGb)
        {
            var json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

            // 1) Fabric Loader - najnowsza stabilna wersja dla 1.21.4
            Status("Pobieram Fabric...", 5);
            var loaders = json.Deserialize<List<Dictionary<string, object>>>(
                GetString("https://meta.fabricmc.net/v2/versions/loader/" + McVersion));
            string loaderVersion = null;
            foreach (var l in loaders)
            {
                var loader = (Dictionary<string, object>)l["loader"];
                if (loader.ContainsKey("stable") && Equals(loader["stable"], true)) { loaderVersion = (string)loader["version"]; break; }
            }
            if (loaderVersion == null) loaderVersion = (string)((Dictionary<string, object>)loaders[0]["loader"])["version"];

            string versionId = "fabric-loader-" + loaderVersion + "-" + McVersion;
            string versionDir = Path.Combine(mcDir, "versions", versionId);
            Directory.CreateDirectory(versionDir);
            string profileJson = GetString("https://meta.fabricmc.net/v2/versions/loader/" + McVersion + "/" + loaderVersion + "/profile/json");
            File.WriteAllText(Path.Combine(versionDir, versionId + ".json"), profileJson, new UTF8Encoding(false));
            string dummyJar = Path.Combine(versionDir, versionId + ".jar");
            if (!File.Exists(dummyJar)) File.WriteAllBytes(dummyJar, new byte[0]);

            // 2) Osobny folder gry WestMC (nie miesza sie z innymi modami gracza)
            string gameDir = Path.Combine(mcDir, "westmc");
            string modsDir = Path.Combine(gameDir, "mods");
            Directory.CreateDirectory(modsDir);
            foreach (var f in Directory.GetFiles(modsDir, "*.jar"))
            {
                string n = Path.GetFileName(f).ToLowerInvariant();
                if (n.StartsWith("fabric-api") || n.StartsWith("westcosmetics") || n.StartsWith("sodium") || n.StartsWith("iris"))
                    File.Delete(f);
            }

            // 3) Mody
            Status("Pobieram Fabric API...", 20);
            DownloadModrinth(json, "fabric-api", modsDir);

            Status("Pobieram kosmetyki WestMC...", 40);
            DownloadWestCosmetics(json, modsDir);

            if (perf)
            {
                Status("Pobieram Sodium...", 60);
                DownloadModrinth(json, "sodium", modsDir);
                Status("Pobieram Iris...", 72);
                DownloadModrinth(json, "iris", modsDir);
            }

            // 4) Serwer westmc.eu na liscie serwerow
            Status("Dodaję serwer westmc.eu...", 85);
            string serversDat = Path.Combine(gameDir, "servers.dat");
            if (!File.Exists(serversDat)) File.WriteAllBytes(serversDat, ServersDat("WestMC", ServerIp));

            // 5) Profil w launcherze
            Status("Dodaję profil WestMC do launchera...", 92);
            foreach (var name in new[] { "launcher_profiles.json", "launcher_profiles_microsoft_store.json" })
            {
                string p = Path.Combine(mcDir, name);
                if (File.Exists(p)) AddProfile(json, p, versionId, gameDir, ramGb);
            }
        }

        private string GetString(string url)
        {
            var r = http.GetAsync(url).Result;
            if (!r.IsSuccessStatusCode) throw new Exception("Serwer " + new Uri(url).Host + " odpowiedział " + (int)r.StatusCode);
            return r.Content.ReadAsStringAsync().Result;
        }

        private void Download(string url, string target)
        {
            var r = http.GetAsync(url).Result;
            if (!r.IsSuccessStatusCode) throw new Exception("Nie udało się pobrać " + Path.GetFileName(target) + " (" + (int)r.StatusCode + ")");
            string tmp = target + ".part";
            using (var fs = File.Create(tmp)) r.Content.CopyToAsync(fs).Wait();
            if (File.Exists(target)) File.Delete(target);
            File.Move(tmp, target);
        }

        /// <summary>Najnowsza wersja moda z Modrinth dla Fabric 1.21.4.</summary>
        private void DownloadModrinth(JavaScriptSerializer json, string slug, string modsDir)
        {
            string url = "https://api.modrinth.com/v2/project/" + slug + "/version?loaders=" +
                         Uri.EscapeDataString("[\"fabric\"]") + "&game_versions=" + Uri.EscapeDataString("[\"" + McVersion + "\"]");
            var versions = json.Deserialize<List<Dictionary<string, object>>>(GetString(url));
            if (versions.Count == 0) throw new Exception("Brak wersji " + slug + " dla " + McVersion);
            var release = versions.FirstOrDefault(v => Equals(v["version_type"], "release")) ?? versions[0];
            var files = ((System.Collections.IEnumerable)release["files"]).Cast<Dictionary<string, object>>().ToList();
            var file = files.FirstOrDefault(f => Equals(f["primary"], true)) ?? files[0];
            Download((string)file["url"], Path.Combine(modsDir, (string)file["filename"]));
        }

        /// <summary>Najnowszy WestCosmetics z GitHub Releases.</summary>
        private void DownloadWestCosmetics(JavaScriptSerializer json, string modsDir)
        {
            var rel = json.Deserialize<Dictionary<string, object>>(GetString("https://api.github.com/repos/" + CosmeticsRepo + "/releases/latest"));
            var assets = ((System.Collections.IEnumerable)rel["assets"]).Cast<Dictionary<string, object>>()
                .Where(a => ((string)a["name"]).EndsWith(".jar") && !((string)a["name"]).Contains("sources"))
                .OrderByDescending(a => (string)a["updated_at"])
                .ToList();
            if (assets.Count == 0) throw new Exception("Brak pliku WestCosmetics w wydaniu.");
            var best = assets[0];
            Download((string)best["browser_download_url"], Path.Combine(modsDir, (string)best["name"]));
        }

        private static void AddProfile(JavaScriptSerializer json, string path, string versionId, string gameDir, int ramGb)
        {
            var root = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(path)) ?? new Dictionary<string, object>();
            Dictionary<string, object> profiles;
            if (root.ContainsKey("profiles") && root["profiles"] is Dictionary<string, object> existing) profiles = existing;
            else { profiles = new Dictionary<string, object>(); root["profiles"] = profiles; }

            string now = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
            string created = now;
            if (profiles.ContainsKey(ProfileId) && profiles[ProfileId] is Dictionary<string, object> old && old.ContainsKey("created"))
                created = old["created"] as string ?? now;

            profiles[ProfileId] = new Dictionary<string, object>
            {
                { "name", ProfileName },
                { "type", "custom" },
                { "created", created },
                { "lastUsed", now },
                { "lastVersionId", versionId },
                { "gameDir", gameDir },
                { "icon", "data:image/png;base64," + LogoBase64() },
                { "javaArgs", "-Xmx" + ramGb + "G -Xms" + Math.Min(ramGb, 2) + "G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M" }
            };

            File.Copy(path, path + ".westmc-backup", true);
            File.WriteAllText(path, json.Serialize(root), new UTF8Encoding(false));
        }

        private static string LogoBase64()
        {
            using (var s = typeof(InstallerForm).Assembly.GetManifestResourceStream("logo.png"))
            using (var src = Image.FromStream(s))
            using (var small = new Bitmap(128, 128))
            {
                using (var g = Graphics.FromImage(small))
                {
                    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    g.DrawImage(src, 0, 0, 128, 128);
                }
                using (var ms = new MemoryStream())
                {
                    small.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
                    return Convert.ToBase64String(ms.ToArray());
                }
            }
        }

        // ------------------------------------------------------------ servers.dat (NBT, bez kompresji)

        private static byte[] ServersDat(string name, string ip)
        {
            using (var ms = new MemoryStream())
            {
                var w = new BigEndianWriter(ms);
                w.Byte(10); w.Str("");                  // TAG_Compound ""
                w.Byte(9); w.Str("servers");            //   TAG_List "servers"
                w.Byte(10); w.Int(1);                   //     typ: Compound, 1 element
                w.Byte(8); w.Str("ip"); w.Str(ip);      //       TAG_String ip
                w.Byte(8); w.Str("name"); w.Str(name);  //       TAG_String name
                w.Byte(0);                              //     koniec elementu
                w.Byte(0);                              // koniec root
                return ms.ToArray();
            }
        }

        private sealed class BigEndianWriter
        {
            private readonly Stream s;
            public BigEndianWriter(Stream s) { this.s = s; }
            public void Byte(int b) { s.WriteByte((byte)b); }
            public void Int(int v) { s.WriteByte((byte)(v >> 24)); s.WriteByte((byte)(v >> 16)); s.WriteByte((byte)(v >> 8)); s.WriteByte((byte)v); }
            public void Str(string v)
            {
                byte[] b = Encoding.UTF8.GetBytes(v);
                s.WriteByte((byte)(b.Length >> 8)); s.WriteByte((byte)b.Length);
                s.Write(b, 0, b.Length);
            }
        }
    }
}
