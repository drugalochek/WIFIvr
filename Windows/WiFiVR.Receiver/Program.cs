using System.Net.Sockets;
using System.Numerics;
using System.Text;
using System.Text.Json;

const int Port = 9000;
using var udp = new UdpClient(Port);

Console.WriteLine($"VRoom tracking receiver listening on UDP :{Port}");
Console.WriteLine("Waiting for head_pose v1 and hand_pose v1 packets on the trusted local network.");

while (true)
{
    var packet = await udp.ReceiveAsync();
    try
    {
        using var json = JsonDocument.Parse(packet.Buffer);
        var root = json.RootElement;
        var type = RequiredString(root, "type");
        var version = RequiredInt(root, "version");

        switch (type, version)
        {
            case ("head_pose", 1):
            {
                var q = root.GetProperty("orientation");
                var orientation = new Quaternion(
                    RequiredFiniteFloat(q, "x"),
                    RequiredFiniteFloat(q, "y"),
                    RequiredFiniteFloat(q, "z"),
                    RequiredFiniteFloat(q, "w"));
                var length = orientation.Length();
                if (length < 0.5f || length > 1.5f)
                    throw new InvalidDataException("Quaternion magnitude is outside the accepted range.");

                orientation = Quaternion.Normalize(orientation);
                var sequence = OptionalLong(root, "sequence");
                TrackingState.UpdateHead(new HeadPose(
                    RequiredLong(root, "timestampNs"), sequence, orientation));
                Console.Write($"\rHEAD {packet.RemoteEndPoint} q=({orientation.X:F2},{orientation.Y:F2},{orientation.Z:F2},{orientation.W:F2})   ");
                break;
            }
            case ("hand_pose", 1):
            {
                var handsElement = root.GetProperty("hands");
                if (handsElement.ValueKind != JsonValueKind.Array || handsElement.GetArrayLength() > 2)
                    throw new InvalidDataException("hands must be an array containing zero, one, or two hands.");

                var hands = new List<TrackedHand>();
                var labels = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var hand in handsElement.EnumerateArray())
                {
                    var handedness = RequiredString(hand, "handedness");
                    if (handedness is not ("Left" or "Right") || !labels.Add(handedness))
                        throw new InvalidDataException("Each hand must have a unique Left or Right label.");

                    var landmarksElement = hand.GetProperty("landmarks");
                    if (landmarksElement.ValueKind != JsonValueKind.Array || landmarksElement.GetArrayLength() != 21)
                        throw new InvalidDataException("Each hand must contain exactly 21 landmarks.");

                    var landmarks = landmarksElement.EnumerateArray()
                        .Select(point => new HandLandmark(
                            RequiredFiniteFloat(point, "x"),
                            RequiredFiniteFloat(point, "y"),
                            RequiredFiniteFloat(point, "z")))
                        .ToArray();

                    if (landmarks.Any(point => point.X is < 0f or > 1f || point.Y is < 0f or > 1f))
                        throw new InvalidDataException("Normalized landmark x/y coordinates must be in [0, 1].");

                    hands.Add(new TrackedHand(handedness, landmarks));
                }

                TrackingState.UpdateHands(new HandPoseFrame(
                    RequiredLong(root, "timestampNs"),
                    RequiredLong(root, "sequence"),
                    hands.ToArray()));
                Console.Write($"\rHANDS {packet.RemoteEndPoint} count={hands.Count}   ");
                break;
            }
            default:
                Console.WriteLine($"\nIgnored unsupported packet: {type} v{version}.");
                break;
        }
    }
    catch (Exception error) when (error is JsonException or InvalidOperationException or
                                  KeyNotFoundException or InvalidDataException or FormatException)
    {
        Console.WriteLine($"\nIgnored invalid tracking packet: {error.Message}");
    }
}

static string RequiredString(JsonElement element, string name)
{
    var value = element.GetProperty(name);
    return value.ValueKind == JsonValueKind.String
        ? value.GetString() ?? throw new InvalidDataException($"{name} cannot be null.")
        : throw new InvalidDataException($"{name} must be a string.");
}

static int RequiredInt(JsonElement element, string name) =>
    element.GetProperty(name).GetInt32();

static long RequiredLong(JsonElement element, string name) =>
    element.GetProperty(name).GetInt64();

static long OptionalLong(JsonElement element, string name) =>
    element.TryGetProperty(name, out var value) ? value.GetInt64() : 0L;

static float RequiredFiniteFloat(JsonElement element, string name)
{
    var value = element.GetProperty(name).GetSingle();
    if (!float.IsFinite(value))
        throw new InvalidDataException($"{name} must be finite.");
    return value;
}

public sealed record HeadPose(long TimestampNs, long Sequence, Quaternion Orientation);
public sealed record HandLandmark(float X, float Y, float Z);
public sealed record TrackedHand(string Handedness, HandLandmark[] Landmarks);
public sealed record HandPoseFrame(long TimestampNs, long Sequence, TrackedHand[] Hands);

public static class TrackingState
{
    private static HeadPose? _head;
    private static HandPoseFrame? _hands;

    public static HeadPose? Head => Volatile.Read(ref _head);
    public static HandPoseFrame? Hands => Volatile.Read(ref _hands);

    public static void UpdateHead(HeadPose pose) => Volatile.Write(ref _head, pose);
    public static void UpdateHands(HandPoseFrame frame) => Volatile.Write(ref _hands, frame);
}
