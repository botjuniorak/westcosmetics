using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace WestSS
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    sealed class MainForm : Form
    {
        static readonly Color Bg = Color.FromArgb(8, 17, 38), Panel = Color.FromArgb(13, 27, 60), Line = Color.FromArgb(35, 68, 132),
            Blue = Color.FromArgb(57, 158, 252), Ink = Color.FromArgb(230, 238, 252), Muted = Color.FromArgb(138, 155, 186),
            Red = Color.FromArgb(255, 91, 107), Gold = Color.FromArgb(255, 196, 64), Green = Color.FromArgb(58, 208, 139);

        readonly string url;
        readonly TextBox code = new TextBox();
        readonly Button start = new Button(), admin = new Button();
        readonly ProgressBar bar = new ProgressBar();
        readonly Label status = new Label(), who = new Label();
        readonly ListView list = new ListView();

        public MainForm()
        {
            url = PanelUrl();
            Text = "WestMC · Sprawdzanie";
            ClientSize = new Size(640, 660);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            ForeColor = Ink;
            Font = new Font("Segoe UI", 10f);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var head = new Label { Text = "WEST", Font = new Font("Segoe UI Black", 22f), ForeColor = Blue, AutoSize = true, Location = new Point(24, 18) };
            var head2 = new Label { Text = "MC", Font = new Font("Segoe UI Black", 22f), ForeColor = Gold, AutoSize = true, Location = new Point(110, 18) };
            var sub = new Label { Text = "Sprawdzanie na obecność cheatów", Font = new Font("Segoe UI Semibold", 11f), ForeColor = Muted, AutoSize = true, Location = new Point(28, 62) };
            var info = new Label
            {
                Text = "Program sprawdza tylko ślady cheatów: mody i wersje Minecrafta, uruchamiane i działające programy, " +
                       "kosz, ostatnio otwierane pliki i Pobrane (same nazwy plików). Nie czyta dokumentów, zdjęć ani haseł. " +
                       "Wynik trafia do administracji WestMC.",
                ForeColor = Muted, Location = new Point(28, 92), Size = new Size(584, 64)
            };
            var codeLbl = new Label { Text = "Kod od administratora:", ForeColor = Ink, AutoSize = true, Location = new Point(28, 168) };
            code.Location = new Point(28, 192);
            code.Size = new Size(200, 40);
            code.Font = new Font("Consolas", 20f, FontStyle.Bold);
            code.CharacterCasing = CharacterCasing.Upper;
            code.MaxLength = 6;
            code.BackColor = Panel;
            code.ForeColor = Ink;
            code.BorderStyle = BorderStyle.FixedSingle;
            code.TextAlign = HorizontalAlignment.Center;

            Style(start, "Rozpocznij sprawdzanie", Blue);
            start.Location = new Point(244, 192);
            start.Size = new Size(200, 41);
            start.Click += async (s, e) => await Begin();
            AcceptButton = start;

            Style(admin, "Uruchom jako administrator", Line);
            admin.Location = new Point(452, 192);
            admin.Size = new Size(160, 41);
            admin.Font = new Font("Segoe UI", 8.5f);
            admin.Click += (s, e) => Elevate();
            admin.Visible = !IsAdmin();

            who.Location = new Point(28, 242); who.Size = new Size(584, 22); who.ForeColor = Muted;
            who.Text = IsAdmin() ? "Uprawnienia administratora: tak (dokładniejsze sprawdzanie)." : "Wskazówka: jako administrator sprawdzanie jest dokładniejsze.";
            bar.Location = new Point(28, 270); bar.Size = new Size(584, 10); bar.Style = ProgressBarStyle.Continuous;
            status.Location = new Point(28, 286); status.Size = new Size(584, 22); status.ForeColor = Muted; status.Text = "Wpisz kod i kliknij „Rozpocznij sprawdzanie”.";

            list.Location = new Point(28, 316); list.Size = new Size(584, 320);
            list.View = View.Details; list.FullRowSelect = true; list.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            list.BackColor = Panel; list.ForeColor = Ink; list.BorderStyle = BorderStyle.None;
            list.Columns.Add("Wynik", 110); list.Columns.Add("Gdzie", 150); list.Columns.Add("Opis", 320);

            Controls.AddRange(new Control[] { head, head2, sub, info, codeLbl, code, start, admin, who, bar, status, list });
        }

        static void Style(Button b, string text, Color c)
        {
            b.Text = text; b.FlatStyle = FlatStyle.Flat; b.BackColor = c; b.ForeColor = Color.White;
            b.FlatAppearance.BorderSize = 0; b.Font = new Font("Segoe UI Semibold", 10f); b.Cursor = Cursors.Hand;
        }

        static bool IsAdmin() => new System.Security.Principal.WindowsPrincipal(System.Security.Principal.WindowsIdentity.GetCurrent())
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);

        void Elevate()
        {
            try
            {
                Process.Start(new ProcessStartInfo(Application.ExecutablePath) { Verb = "runas", UseShellExecute = true });
                Close();
            }
            catch { status.Text = "Nie uruchomiono jako administrator. Możesz kontynuować bez tego."; }
        }

        /// <summary>Panel dopisuje swoj adres na koncu pliku .exe przy pobieraniu: #WESTSS#http://...#</summary>
        static string PanelUrl()
        {
            try
            {
                using (var fs = File.OpenRead(Application.ExecutablePath))
                {
                    int n = (int)Math.Min(512, fs.Length);
                    fs.Seek(-n, SeekOrigin.End);
                    byte[] b = new byte[n];
                    fs.Read(b, 0, n);
                    var m = Regex.Match(Encoding.ASCII.GetString(b), "#WESTSS#(https?://[A-Za-z0-9.:\\-]+)#");
                    if (m.Success) return m.Groups[1].Value;
                }
            }
            catch { }
            return "http://159.69.142.97:20001";
        }

        static string Http(string method, string address, string body)
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            using (var wc = new WebClient())
            {
                wc.Encoding = Encoding.UTF8;
                wc.Headers[HttpRequestHeader.ContentType] = "application/json; charset=utf-8";
                wc.Headers[HttpRequestHeader.UserAgent] = "WestSS/1.0";
                try { return method == "GET" ? wc.DownloadString(address) : wc.UploadString(address, body); }
                catch (WebException e) when (e.Response != null)
                {
                    using (var r = new StreamReader(e.Response.GetResponseStream(), Encoding.UTF8)) return r.ReadToEnd();
                }
            }
        }

        static string JsonStr(string json, string key)
        {
            var m = Regex.Match(json ?? "", "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            return m.Success ? Regex.Unescape(m.Groups[1].Value) : null;
        }

        async Task Begin()
        {
            string k = code.Text.Trim().ToUpperInvariant();
            if (!Regex.IsMatch(k, "^[A-Z0-9]{6}$")) { status.ForeColor = Red; status.Text = "Kod ma 6 znaków. Dostaniesz go od administratora."; return; }
            start.Enabled = false; code.Enabled = false; status.ForeColor = Muted; status.Text = "Sprawdzam kod...";
            string check;
            try { check = await Task.Run(() => Http("GET", url + "/ss/kod?k=" + k, null)); }
            catch (Exception ex) { Fail("Nie można połączyć się z serwerem WestMC (" + ex.Message + ")."); return; }
            string nick = JsonStr(check, "nick");
            if (nick == null) { Fail(JsonStr(check, "error") ?? "Zły albo wygasły kod."); return; }
            who.Text = "Sprawdzanie gracza: " + nick + (IsAdmin() ? " · jako administrator" : "");
            who.ForeColor = Ink;

            var sc = new Scanner();
            sc.Progress = (s, p) => BeginInvoke((Action)(() => { status.Text = s + "..."; bar.Value = Math.Max(0, Math.Min(100, p)); }));
            await Task.Run(() => sc.Run());

            list.Items.Clear();
            foreach (Finding f in sc.Findings.OrderBy(x => x.Level == "cheat" ? 0 : x.Level == "podejrzane" ? 1 : 2))
            {
                var it = new ListViewItem(f.Level == "cheat" ? "CHEAT" : f.Level == "podejrzane" ? "Podejrzane" : "Info");
                it.SubItems.Add(f.Category);
                it.SubItems.Add(f.Text + (f.When != null ? " (" + f.When + ")" : ""));
                it.ForeColor = f.Level == "cheat" ? Red : f.Level == "podejrzane" ? Gold : Muted;
                list.Items.Add(it);
            }
            if (list.Items.Count == 0) list.Items.Add(new ListViewItem(new[] { "Czysto", "—", "Nie znaleziono śladów cheatów." }) { ForeColor = Green });

            status.Text = "Wysyłam wynik do administracji...";
            string res;
            try { res = await Task.Run(() => Http("POST", url + "/ss/raport", sc.ToJson(k))); }
            catch (Exception ex) { Fail("Nie wysłano wyniku (" + ex.Message + "). Spróbuj jeszcze raz."); return; }
            if (res != null && res.Contains("\"ok\"")) { status.ForeColor = Green; status.Text = "Gotowe! Wynik wysłany. Nie zamykaj gry ani Discorda, administrator zaraz go sprawdzi."; }
            else Fail(JsonStr(res, "error") ?? "Serwer nie przyjął wyniku.");
        }

        void Fail(string msg)
        {
            status.ForeColor = Red;
            status.Text = msg;
            start.Enabled = true;
            code.Enabled = true;
        }
    }
}
