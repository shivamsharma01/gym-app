import csv
import sqlite3
import base64
import binascii
from pathlib import Path


# ============================================================
# CONFIGURATION
# ============================================================

# Resolve paths relative to this Python script.
# This works on both Windows and Linux.
BASE_DIR = Path(__file__).resolve().parent

DB_FILE = BASE_DIR / "data" / "devices.db"
CSV_FILE = BASE_DIR / "data" / "users_imported_device.csv"


# Every CSV user is updated on these devices.
DEVICES = [
    "dev-test-device-entry-01",
    "dev-test-device-exit-01",
]


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

    # --------------------------------------------------------
    # Handle Data URI
    # --------------------------------------------------------

    if value.lower().startswith("data:image/"):

        if "," not in value:
            return None

        value = value.split(",", 1)[1]

    # --------------------------------------------------------
    # Remove whitespace/newlines from Base64
    # --------------------------------------------------------

    value = "".join(value.split())

    if not value:
        return None

    # --------------------------------------------------------
    # Fix missing Base64 padding
    # --------------------------------------------------------

    padding = len(value) % 4

    if padding:
        value += "=" * (4 - padding)

    # --------------------------------------------------------
    # Decode Base64
    # --------------------------------------------------------

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

    # --------------------------------------------------------
    # Verify JPEG magic bytes
    #
    # JPEG starts with:
    #
    # FF D8
    # --------------------------------------------------------

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

    # --------------------------------------------------------
    # Common JPEG Base64 prefix
    # --------------------------------------------------------

    if value.startswith("/9j/"):

        return decode_photo(value)

    # --------------------------------------------------------
    # Data URI
    # --------------------------------------------------------

    if value.lower().startswith("data:image/"):

        return decode_photo(value)

    # --------------------------------------------------------
    # Some exports may contain Base64 without the /9j/
    # prefix. Only attempt this for reasonably large values.
    # --------------------------------------------------------

    if len(value) < 100:
        return None

    try:

        decoded = base64.b64decode(
            value,
            validate=False
        )

    except Exception:

        return None

    # Verify JPEG magic bytes.
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

    print("------------------------------------------")
    print("USERS TABLE COLUMNS")
    print("------------------------------------------")

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

    print("------------------------------------------")
    print("CURRENT DATABASE PHOTO TYPES")
    print("------------------------------------------")

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
# MAIN
# ============================================================

def main():

    print()
    print("==========================================")
    print("GYM DEVICE PHOTO IMPORT")
    print("==========================================")
    print()

    # ========================================================
    # CHECK FILES
    # ========================================================

    if not DB_FILE.exists():

        print("ERROR: Database not found:")
        print(DB_FILE)
        return

    if not CSV_FILE.exists():

        print("ERROR: CSV file not found:")
        print(CSV_FILE)
        return

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


    # ========================================================
    # CONNECT TO SQLITE
    # ========================================================

    conn = sqlite3.connect(DB_FILE)

    cursor = conn.cursor()


    # ========================================================
    # COUNTERS
    # ========================================================

    csv_users = 0
    photos_found = 0
    photos_not_found = 0
    photo_rows_updated = 0
    users_not_found = 0
    errors = 0


    try:

        # ====================================================
        # CHECK DATABASE
        # ====================================================

        check_database(cursor)

        show_table_info(cursor)

        show_existing_photos(cursor)


        # ====================================================
        # READ CSV
        # ====================================================

        print("------------------------------------------")
        print("PROCESSING CSV")
        print("------------------------------------------")

        with open(
            CSV_FILE,
            "r",
            encoding="utf-8-sig",
            newline=""
        ) as f:

            reader = csv.reader(f)

            for line_number, row in enumerate(
                reader,
                start=1
            ):

                # ------------------------------------------------
                # Ignore empty rows
                # ------------------------------------------------

                if not row:
                    continue

                if not any(
                    str(value).strip()
                    for value in row
                ):
                    continue


                # ------------------------------------------------
                # USER ID
                # ------------------------------------------------

                if len(row) == 0:

                    continue

                user_id = row[0].strip()

                if not user_id:

                    print(
                        f"Line {line_number}: "
                        f"SKIPPED - empty user ID"
                    )

                    continue


                csv_users += 1


                # ------------------------------------------------
                # FIND PHOTO
                # ------------------------------------------------

                photo, photo_column = find_photo(row)


                # ------------------------------------------------
                # PHOTO NOT FOUND
                # ------------------------------------------------

                if photo is None:

                    photos_not_found += 1

                    print(
                        f"Line {line_number}: "
                        f"user_id={user_id} | "
                        f"PHOTO NOT FOUND"
                    )

                    continue


                photos_found += 1


                # ------------------------------------------------
                # JPEG VALIDATION
                # ------------------------------------------------

                if not photo.startswith(
                    b"\xff\xd8"
                ):

                    errors += 1

                    print(
                        f"Line {line_number}: "
                        f"user_id={user_id} | "
                        f"INVALID JPEG"
                    )

                    continue


                print(
                    f"Line {line_number}: "
                    f"user_id={user_id} | "
                    f"photo_column={photo_column} | "
                    f"JPEG={len(photo):,} bytes"
                )


                # =================================================
                # UPDATE BOTH DEVICES
                #
                # IMPORTANT:
                # Only the photo column is changed.
                # =================================================

                for device_id in DEVICES:

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


                    if cursor.rowcount == 0:

                        users_not_found += 1

                        print(
                            f"    WARNING: "
                            f"DB user not found: "
                            f"{device_id} / {user_id}"
                        )

                    else:

                        photo_rows_updated += (
                            cursor.rowcount
                        )

                        print(
                            f"    UPDATED: "
                            f"{device_id} / {user_id}"
                        )


        # ====================================================
        # COMMIT
        # ====================================================

        conn.commit()


        # ====================================================
        # VERIFY
        # ====================================================

        print()
        print("------------------------------------------")
        print("VERIFYING UPDATED PHOTO DATA")
        print("------------------------------------------")

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

        verification_rows = cursor.fetchall()

        if not verification_rows:

            print("No photos found in database.")

        else:

            for row in verification_rows:

                print(
                    f"device={row[0]} | "
                    f"user={row[1]} | "
                    f"type={row[2]} | "
                    f"bytes={row[3]}"
                )


        # ====================================================
        # FINAL SUMMARY
        # ====================================================

        print()
        print("==========================================")
        print("PHOTO UPDATE COMPLETE")
        print("==========================================")

        print(
            f"CSV users processed : {csv_users}"
        )

        print(
            f"Photos found        : {photos_found}"
        )

        print(
            f"Photos not found    : {photos_not_found}"
        )

        print(
            f"Photo rows updated  : {photo_rows_updated}"
        )

        print(
            f"Users not found     : {users_not_found}"
        )

        print(
            f"Errors              : {errors}"
        )

        print("==========================================")
        print()


    except Exception as e:

        # ====================================================
        # ROLLBACK
        # ====================================================

        conn.rollback()

        print()
        print("==========================================")
        print("ERROR")
        print("==========================================")

        print(
            f"{type(e).__name__}: {e}"
        )

        print()
        print("All changes have been rolled back.")
        print("==========================================")
        print()


    finally:

        conn.close()


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    main()
