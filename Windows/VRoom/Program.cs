using System.Net;
using System.Net.Sockets;
using System.Numerics;
using System.Text.Json;

namespace VRoom;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new DashboardForm());
    }
}

internal sealed class DashboardForm : Form
{
    private readonly Label _status = MakeValue("Остановлено");
    private readonly Label _head = MakeValue("Нет данных");
    private readonly Label _hands = MakeValue("Нет данных");
    private readonly Label _endpoint = MakeValue("UDP :9000");
    private readonly TextBox _log = new()
    {
        Multiline = true, ReadOnly = true, WordWrap = false,
        ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill,
        BackColor = Color.FromArgb(12, 18, 30), ForeColor = Color.Gainsboro,
        BorderStyle = BorderStyle.None, Font = new Font("Consolas", 9.5f)
    };
    private readonly Button _toggle = new() { Text = "Запустить приёмник", AutoSize = true };
    private CancellationTokenSource? _stop;
    private Task? _listener;
    private long _headPackets, _handPackets, _invalidPackets;
    private DateTime _lastPacket = DateTime.MinValue;

    public DashboardForm()
    {
        Text = "VRoom для Windows";
        MinimumSize = new Size(760, 540);
        Size = new Size(980, 680);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(17, 25, 41);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(22), ColumnCount = 1, RowCount = 4 };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var brand = new Label
        {
            Text = "VRoom", AutoSize = true, Font = new Font("Segoe UI", 28, FontStyle.Bold),
            ForeColor = Color.FromArgb(73, 226, 178), Padding = new Padding(0, 0, 0, 4)
        };
        root.Controls.Add(brand);

        var subtitle = new Label
        {
            Text = "Панель ПК · приём телеметрии телефона по локальной сети",
            AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(0, 0, 0, 18)
        };
        root.Controls.Add(subtitle);

        var cards = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 2, Padding = new Padding(0, 0, 0, 14) };
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.Controls.Add(MakeCard("СОСТОЯНИЕ", _status), 0, 0);
        cards.Controls.Add(MakeCard("ПОСЛЕДНЯЯ ОРИЕНТАЦИЯ", _head), 1, 0);
        cards.Controls.Add(MakeCard("РУКИ / LANDMARKS", _hands), 0, 1);
        cards.Controls.Add(MakeCard("СЕТЕВОЙ ПОРТ", _endpoint), 1, 1);
        root.Controls.Add(cards);

        var lower = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        lower.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        lower.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 0, 0, 12) };
        _toggle.BackColor = Color.FromArgb(73, 226, 178);
        _toggle.ForeColor = Color.FromArgb(8, 28, 28);
        _toggle.FlatStyle = FlatStyle.Flat;
        _toggle.Font = new Font("Segoe UI", 10, FontStyle.Bold);
        _toggle.Click += async (_, _) => await ToggleListenerAsync();
        actions.Controls.Add(_toggle);
        var hint = new Label { Text = "Телефон и ПК должны быть в одной Wi-Fi сети. Разреши доступ в брандмауэре для частной сети.", AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(10, 8, 0, 0) };
        actions.Controls.Add(hint);
        lower.Controls.Add(actions, 0, 0);
        lower.Controls.Add(_log, 0, 1);
        root.Controls.Add(lower);
        Log("VRoom запущен. Нажми «Запустить приёмник», затем подключи телефон к IP этого ПК.");
    }

    private static Label MakeValue(string value) => new()
    {
        Text = value, AutoSize = true, ForeColor = Color.White,
        Font = new Font("Segoe UI", 12, FontStyle.Bold), MaximumSize = new Size(420, 0)
    };

    private static Control MakeCard(string title, Label value)
    {
        var panel = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(27, 39, 64), Padding = new Padding(14), Margin = new Padding(0, 0, 12, 12), AutoSize = true };
        var stack = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true };
        stack.Controls.Add(new Label { Text = title, AutoSize = true, ForeColor = Color.FromArgb(145, 171, 199), Font = new Font("Segoe UI", 8, FontStyle.Bold) });
        stack.Controls.Add(value);
        panel.Controls.Add(stack);
        return panel;
    }

    private async Task ToggleListenerAsync()
    {
        if (_stop is not null)
        {
            _stop.Cancel();
            _toggle.Enabled = false;
            if (_listener is not null) { try { await _listener; } catch { } }
            _stop.Dispose(); _stop = null; _listener = null;
            _toggle.Enabled = true; _toggle.Text = "Запустить приёмник";
            _status.Text = "Остановлено";
            Log("Приёмник остановлен.");
            return;
        }

        _stop = new CancellationTokenSource();
        _toggle.Text = "Остановить приёмник";
        _status.Text = "Слушает UDP :9000";
        _endpoint.Text = "UDP :9000 · " + string.Join(", ", GetLocalAddresses());
        Log("Приём UDP запущен на порту 9000.");
        _listener = ListenAsync(_stop.Token);
    }

    private static string[] GetLocalAddresses() =>
        Dns.GetHostEntry(Dns.GetHostName()).AddressList
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
            .Select(a => a.ToString()).Distinct().ToArray();

    private async Task ListenAsync(CancellationToken token)
    {
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, 9000));
        using var registration = token.Register(() => udp.Close());
        try
        {
            while (!token.IsCancellationRequested)
            {
                var packet = await udp.ReceiveAsync(token);
                try
                {
                    using var json = JsonDocument.Parse(packet.Buffer);
                    var root = json.RootElement;
                    var type = RequiredString(root, "type");
                    var version = root.GetProperty("version").GetInt32();
                    switch (type, version)
                    {
                        case ("head_pose", 1):
                        {
                            var q = root.GetProperty("orientation");
                            var pose = new Quaternion(Finite(q, "x"), Finite(q, "y"), Finite(q, "z"), Finite(q, "w"));
                            if (pose.Length() is < 0.5f or > 1.5f) throw new InvalidDataException("Некорректная длина quaternion");
                            pose = Quaternion.Normalize(pose);
                            Interlocked.Increment(ref _headPackets);
                            _lastPacket = DateTime.Now;
                            UpdateUi(() => _head.Text = $"x {pose.X:F2} · y {pose.Y:F2} · z {pose.Z:F2} · w {pose.W:F2}");
                            break;
                        }
                        case ("hand_pose", 1):
                        {
                            var array = root.GetProperty("hands");
                            if (array.ValueKind != JsonValueKind.Array || array.GetArrayLength() > 2) throw new InvalidDataException("Некорректный список рук");
                            var descriptions = new List<string>();
                            foreach (var hand in array.EnumerateArray())
                            {
                                var label = RequiredString(hand, "handedness");
                                if (label is not ("Left" or "Right")) throw new InvalidDataException("Неизвестная рука");
                                var points = hand.GetProperty("landmarks");
                                if (points.ValueKind != JsonValueKind.Array || points.GetArrayLength() != 21) throw new InvalidDataException("Ожидалось 21 точка на руку");
                                var wrist = points[0];
                                descriptions.Add($"{label}: запястье ({Finite(wrist, "x"):F2}, {Finite(wrist, "y"):F2}, {Finite(wrist, "z"):F2})");
                                foreach (var point in points.EnumerateArray()) { _ = Finite(point, "x"); _ = Finite(point, "y"); _ = Finite(point, "z"); }
                            }
                            Interlocked.Increment(ref _handPackets);
                            _lastPacket = DateTime.Now;
                            UpdateUi(() => _hands.Text = array.GetArrayLength() == 0 ? "Руки не обнаружены" : string.Join(" | ", descriptions));
                            break;
                        }
                        default: throw new InvalidDataException($"Неизвестный пакет {type} v{version}");
                    }
                }
                catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or InvalidDataException or FormatException or OverflowException)
                {
                    Interlocked.Increment(ref _invalidPackets);
                    Log($"Отклонён пакет от {packet.RemoteEndPoint}: {ex.Message}");
                }
            }
        }
        catch (ObjectDisposedException) when (token.IsCancellationRequested) { }
        catch (SocketException) when (token.IsCancellationRequested) { }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception ex) { Log("Ошибка UDP: " + ex.Message); UpdateUi(() => _status.Text = "Ошибка приёмника"); }
    }

    private static string RequiredString(JsonElement element, string key) =>
        element.GetProperty(key).GetString() ?? throw new InvalidDataException($"{key} пуст");
    private static float Finite(JsonElement element, string key)
    {
        var value = element.GetProperty(key).GetSingle();
        return float.IsFinite(value) ? value : throw new InvalidDataException($"{key} не является конечным числом");
    }

    private void UpdateUi(Action action)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(action); } catch (InvalidOperationException) { }
    }

    private void Log(string message)
    {
        UpdateUi(() =>
        {
            _log.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
            if (_log.Lines.Length > 500) _log.Lines = _log.Lines[^400..];
        });
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _stop?.Cancel();
        base.OnFormClosing(e);
    }
}
