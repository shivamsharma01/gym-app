using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace Common
{ 
    public class TimeZoneException : System.Exception
    {
        public TimeZoneException(string message) : base(message)
        {
        }
    }

    public class Common
    {
        public static DateTime GetZoneTimeByUTCTime(DateTime utcDateTime)
        {
            System.Collections.ObjectModel.ReadOnlyCollection<TimeZoneInfo> systemTimeZones = TimeZoneInfo.GetSystemTimeZones();
            foreach (TimeZoneInfo zoneInfo in systemTimeZones)
            {
                Console.WriteLine();
                if (TimeZoneInfo.Local.DaylightName == zoneInfo.DaylightName)
                {
                    return TimeZoneInfo.ConvertTimeFromUtc(utcDateTime, TimeZoneInfo.Local);
                }
            }

            TimeZoneException exp = new TimeZoneException("The target time zone is not supported()");
            throw exp;
        }

        public static DateTime GetUTCTime(DateTime dateTime)
        {
            DateTime retDateTime = TimeZoneInfo.ConvertTimeToUtc(dateTime);
            return retDateTime;
        }

        public static void ChangeFont(Control parent, Font newFont)
        {
            foreach (Control ctrl in parent.Controls)
            {
                ctrl.Font = newFont;
                if (ctrl.HasChildren)
                {
                    ChangeFont(ctrl, newFont);
                }
            }
        }
    }
}
