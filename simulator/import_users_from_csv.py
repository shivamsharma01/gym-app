import csv
import sqlite3
import base64
import binascii
from pathlib import Path


# ============================================================
# CONFIGURATION
# ============================================================

BASE_DIR = Path(__file__).resolve().parent

DB_FILE = BASE_DIR / "data" / "devices.db"
CSV_FILE = BASE_DIR / "data" / "users_imported_device.csv"

DEVICES = [
    "dev-test-device-entry-01",
    "dev-test-device-exit-01",
]

SEPARATOR = "=========================================="
SECTION_SEPARATOR = "------------------------------------------"


# ============================================================
# BASE64 / JPEG HELPERS
# ============================================================

def decode_photo(value):
    """
    Convert a Base64 JPEG string into raw JPEG bytes.

    Supports:
        /9j/4AAQSkZJRg...

    and:
        data:image/jpeg;base64,/9j/4AAQSkZJRg...

    Returns:
        bytes if valid JPEG
        None otherwise
    """

    if value is None:
        return None

    value = str(value).strip()

    if not value:
        return None

    # Handle Data URI
    if value.lower().startswith("data:image/"):

        if "," not in value:
            return None

        value = value.split(",", 1)[1]

    # Remove whitespace/newlines from Base64
    value = "".join(value.split())

    if not value:
        return None

    # Fix missing Base64 padding
    padding = len(value) % 4

    if padding:
        value += "=" * (4 - padding)

    # Decode Base64
    try:

        photo = base64.b64decode(
            value,
            validate=True
        )

    except (ValueError, binascii.Error):

        # Some exports can contain characters that strict
        # Base64 validation rejects. Try a non-strict decode.
        try:

            photo = base64.b64decode(
                value,
                validate=False
            )

        except Exception:

            return None

    if not photo:
        return None

    # Verify JPEG magic bytes
    if not photo.startswith(b"\xff\xd8"):
        return None

    return photo


def try_decode_photo(value):
    """
    Determine whether a CSV value contains a JPEG Base64
    string and decode it if possible.
    """

    if value is None:
        return None

    value = str(value).strip()

    if not value:
        return None

    # Common JPEG Base64 prefix
    if value.startswith("/9j/"):
        return decode_photo(value)

    # Data URI
    if value.lower().startswith("data:image/"):
        return decode_photo(value)

    # Some exports may contain Base64 without the /9j/
    # prefix. Only attempt this for reasonably large values.
    if len(value) < 100:
        return None

    try:

        decoded = base64.b64decode(
            value,
            validate=False
        )

    except Exception:

        return None

    # Verify JPEG magic bytes
    if decoded.startswith(b"\xff\xd8"):
        return decoded

    return None


def find_photo(row):
    """
    Search every column in a CSV row for a JPEG Base64 value.

    Returns:
        (photo_bytes, column_index)

    or:
        (None, None)
    """

    for column_index, value in enumerate(row):

        if value is None:
            continue

        value = str(value).strip()

        if not value:
            continue

        photo = try_decode_photo(value)

        if photo is not None:
            return photo, column_index

    return None, None


# ============================================================
# DATABASE CHECKS
# ============================================================

def check_database(cursor):
    """
    Verify that the users table exists.
    """

    cursor.execute(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
          AND name = 'users'
        """
    )

    if cursor.fetchone() is None:
        raise RuntimeError(
            "users table does not exist"
        )


def show_table_info(cursor):
    """
    Display the users table columns.
    """

    print(SECTION_SEPARATOR)
    print("USERS TABLE COLUMNS")
    print(SECTION_SEPARATOR)

    cursor.execute(
        "PRAGMA table_info(users)"
    )

    columns = cursor.fetchall()

    if not columns:
        print("No columns found.")
        return

    for column in columns:

        # PRAGMA table_info:
        # 0 = cid
        # 1 = name
        # 2 = type
        # 3 = notnull
        # 4 = default
        # 5 = pk

        print(
            f"  {column[1]} "
            f"({column[2]})"
        )

    print()


def show_existing_photos(cursor):
    """
    Display a small sample of existing photo data before
    updating the database.
    """

    print(SECTION_SEPARATOR)
    print("CURRENT DATABASE PHOTO TYPES")
    print(SECTION_SEPARATOR)

    cursor.execute(
        """
        SELECT
            device_id,
            user_id,
            typeof(photo),
            length(photo)
        FROM users
        WHERE photo IS NOT NULL
        LIMIT 10
        """
    )

    rows = cursor.fetchall()

    if not rows:
        print("No existing photos found.")
    else:
        for row in rows:

            print(
                f"device={row[0]} | "
                f"user={row[1]} | "
                f"type={row[2]} | "
                f"size={row[3]}"
            )

    print()


# ============================================================
# FILE / CONFIGURATION HELPERS
# ============================================================

def validate_files():
    """
    Verify that the database and CSV files exist.
    """

    if not DB_FILE.exists():

        print("ERROR: Database not found:")
        print(DB_FILE)

        return False

    if not CSV_FILE.exists():

        print("ERROR: CSV file not found:")
        print(CSV_FILE)

        return False

    return True


def print_configuration():
    """
    Display import configuration.
    """

    print()
    print(SEPARATOR)
    print("GYM DEVICE PHOTO IMPORT")
    print(SEPARATOR)
    print()

    print("Database:")
    print(DB_FILE)

    print()

    print("CSV:")
    print(CSV_FILE)

    print()

    print("Devices:")

    for device_id in DEVICES:
        print(f"  {device_id}")

    print()


# ============================================================
# CSV HELPERS
# ============================================================

def is_valid_csv_row(row):
    """
    Return True when a CSV row contains data.
    """

    if not row:
        return False

    return any(
        str(value).strip()
        for value in row
    )


def get_user_id(row, line_number):
    """
    Extract and validate user ID from a CSV row.
    """

    user_id = row[0].strip()

    if not user_id:

        print(
            f"Line {line_number}: "
            f"SKIPPED - empty user ID"
        )

        return None

    return user_id


# ============================================================
# DATABASE UPDATE HELPERS
# ============================================================

def update_device_photo(
    cursor,
    device_id,
    user_id,
    photo
):
    """
    Update the photo for one device.
    """

    cursor.execute(
        """
        UPDATE users
        SET photo = ?
        WHERE device_id = ?
          AND user_id = ?
        """,
        (
            sqlite3.Binary(photo),
            device_id,
            user_id,
        )
    )

    return cursor.rowcount


def update_user_devices(
    cursor,
    user_id,
    photo
):
    """
    Update the photo for all configured devices.

    Returns:
        (updated_rows, users_not_found)
    """

    updated_rows = 0
    users_not_found = 0

    for device_id in DEVICES:

        row_count = update_device_photo(
            cursor,
            device_id,
            user_id,
            photo
        )

        if row_count == 0:

            users_not_found += 1

            print(
                f"    WARNING: "
                f"DB user not found: "
                f"{device_id} / {user_id}"
            )

        else:

            updated_rows += row_count

            print(
                f"    UPDATED: "
                f"{device_id} / {user_id}"
            )

    return updated_rows, users_not_found


# ============================================================
# CSV ROW PROCESSING
# ============================================================

def process_csv_row(
    cursor,
    row,
    line_number
):
    """
    Process one CSV row.

    Returns:
        (
            processed_users,
            photos_found,
            photos_not_found,
            rows_updated,
            users_not_found,
            errors
        )
    """

    if not is_valid_csv_row(row):
        return 0, 0, 0, 0, 0, 0

    user_id = get_user_id(
        row,
        line_number
    )

    if user_id is None:
        return 0, 0, 0, 0, 0, 0

    photo, photo_column = find_photo(row)

    if photo is None:

        print(
            f"Line {line_number}: "
            f"user_id={user_id} | "
            f"PHOTO NOT FOUND"
        )

        return 1, 0, 1, 0, 0, 0

    print(
        f"Line {line_number}: "
        f"user_id={user_id} | "
        f"photo_column={photo_column} | "
        f"JPEG={len(photo):,} bytes"
    )

    rows_updated, users_not_found = (
        update_user_devices(
            cursor,
            user_id,
            photo
        )
    )

    return (
        1,
        1,
        0,
        rows_updated,
        users_not_found,
        0,
    )


def process_csv(cursor):
    """
    Process all users from the CSV file.
    """

    stats = {
        "csv_users": 0,
        "photos_found": 0,
        "photos_not_found": 0,
        "photo_rows_updated": 0,
        "users_not_found": 0,
        "errors": 0,
    }

    print(SECTION_SEPARATOR)
    print("PROCESSING CSV")
    print(SECTION_SEPARATOR)

    with open(
        CSV_FILE,
        "r",
        encoding="utf-8-sig",
        newline=""
    ) as file:

        reader = csv.reader(file)

        for line_number, row in enumerate(
            reader,
            start=1
        ):

            result = process_csv_row(
                cursor,
                row,
                line_number
            )

            (
                users,
                photos_found,
                photos_not_found,
                rows_updated,
                users_not_found,
                errors,
            ) = result

            stats["csv_users"] += users
            stats["photos_found"] += photos_found
            stats["photos_not_found"] += photos_not_found
            stats["photo_rows_updated"] += rows_updated
            stats["users_not_found"] += users_not_found
            stats["errors"] += errors

    return stats


# ============================================================
# VERIFICATION
# ============================================================

def verify_photo_data(cursor):
    """
    Verify photo data after the update.
    """

    print()
    print(SECTION_SEPARATOR)
    print("VERIFYING UPDATED PHOTO DATA")
    print(SECTION_SEPARATOR)

    cursor.execute(
        """
        SELECT
            device_id,
            user_id,
            typeof(photo),
            length(photo)
        FROM users
        WHERE photo IS NOT NULL
        ORDER BY device_id, user_id
        """
    )

    rows = cursor.fetchall()

    if not rows:

        print("No photos found in database.")
        return

    for row in rows:

        print(
            f"device={row[0]} | "
            f"user={row[1]} | "
            f"type={row[2]} | "
            f"bytes={row[3]}"
        )


# ============================================================
# SUMMARY
# ============================================================

def print_summary(stats):
    """
    Display final import statistics.
    """

    print()
    print(SEPARATOR)
    print("PHOTO UPDATE COMPLETE")
    print(SEPARATOR)

    print(
        f"CSV users processed : "
        f"{stats['csv_users']}"
    )

    print(
        f"Photos found        : "
        f"{stats['photos_found']}"
    )

    print(
        f"Photos not found    : "
        f"{stats['photos_not_found']}"
    )

    print(
        f"Photo rows updated  : "
        f"{stats['photo_rows_updated']}"
    )

    print(
        f"Users not found     : "
        f"{stats['users_not_found']}"
    )

    print(
        f"Errors              : "
        f"{stats['errors']}"
    )

    print(SEPARATOR)
    print()


# ============================================================
# ERROR HANDLING
# ============================================================

def handle_import_error(error):
    """
    Display import error information.
    """

    print()
    print(SEPARATOR)
    print("ERROR")
    print(SEPARATOR)

    print(
        f"{type(error).__name__}: "
        f"{error}"
    )

    print()
    print("All changes have been rolled back.")
    print(SEPARATOR)
    print()


# ============================================================
# MAIN
# ============================================================

def main():
    """
    Run the gym device photo import.
    """

    if not validate_files():
        return

    print_configuration()

    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()

    try:

        check_database(cursor)

        show_table_info(cursor)

        show_existing_photos(cursor)

        stats = process_csv(cursor)

        conn.commit()

        verify_photo_data(cursor)

        print_summary(stats)

    except Exception as error:

        conn.rollback()

        handle_import_error(error)

    finally:

        conn.close()


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    main()
