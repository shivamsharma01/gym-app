using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class DoorOpenTimeSectionForm : Form
    {
        public NET_CFG_DOOROPEN_TIMESECTION_INFO[] m_TimeSections;

        public DoorOpenTimeSectionForm()
        {
            InitializeComponent();
            m_TimeSections = new NET_CFG_DOOROPEN_TIMESECTION_INFO[1];
        }

        public DoorOpenTimeSectionForm(NET_CFG_DOOROPEN_TIMESECTION_INFO[] sections)
        {
            InitializeComponent();
            m_TimeSections = sections;
        }

        private void cmb_WeekDay_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (cmb_WeekDay.SelectedIndex < 0 || cmb_WeekDay.SelectedIndex > 6)
            {
                return;
            }
            var temp = m_TimeSections[cmb_WeekDay.SelectedIndex * 4];
            starttime1.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuStartTime.dwHour, (int)temp.stuTime.stuStartTime.dwMinute, (int)temp.stuTime.stuStartTime.dwSecond);
            endtime1.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuEndTime.dwHour, (int)temp.stuTime.stuEndTime.dwMinute, (int)temp.stuTime.stuEndTime.dwSecond);
            dooropenmethod1.SelectedIndex = (int)temp.emDoorOpenMethod;

            temp = m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1];
            starttime2.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuStartTime.dwHour, (int)temp.stuTime.stuStartTime.dwMinute, (int)temp.stuTime.stuStartTime.dwSecond);
            endtime2.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuEndTime.dwHour, (int)temp.stuTime.stuEndTime.dwMinute, (int)temp.stuTime.stuEndTime.dwSecond);
            dooropenmethod2.SelectedIndex = (int)temp.emDoorOpenMethod;

            temp = m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2];
            starttime3.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuStartTime.dwHour, (int)temp.stuTime.stuStartTime.dwMinute, (int)temp.stuTime.stuStartTime.dwSecond);
            endtime3.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuEndTime.dwHour, (int)temp.stuTime.stuEndTime.dwMinute, (int)temp.stuTime.stuEndTime.dwSecond);
            dooropenmethod3.SelectedIndex = (int)temp.emDoorOpenMethod;

            temp = m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3];
            starttime4.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuStartTime.dwHour, (int)temp.stuTime.stuStartTime.dwMinute, (int)temp.stuTime.stuStartTime.dwSecond);
            endtime4.Value = new DateTime(2020, 1, 1, (int)temp.stuTime.stuEndTime.dwHour, (int)temp.stuTime.stuEndTime.dwMinute, (int)temp.stuTime.stuEndTime.dwSecond);
            dooropenmethod4.SelectedIndex = (int)temp.emDoorOpenMethod;
        }

        private void btn_Confirm_Click(object sender, EventArgs e)
        {
            if (cmb_WeekDay.SelectedIndex < 0 || cmb_WeekDay.SelectedIndex > 6)
            {
                return;
            }

            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuStartTime.dwHour = (uint)starttime1.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuStartTime.dwMinute = (uint)starttime1.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuStartTime.dwSecond = (uint)starttime1.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuEndTime.dwHour = (uint)endtime1.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuEndTime.dwMinute = (uint)endtime1.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].stuTime.stuEndTime.dwSecond = (uint)endtime1.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4].emDoorOpenMethod = (EM_CFG_DOOR_OPEN_METHOD)dooropenmethod1.SelectedIndex;

            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuStartTime.dwHour = (uint)starttime2.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuStartTime.dwMinute = (uint)starttime2.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuStartTime.dwSecond = (uint)starttime2.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuEndTime.dwHour = (uint)endtime2.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuEndTime.dwMinute = (uint)endtime2.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].stuTime.stuEndTime.dwSecond = (uint)endtime2.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 1].emDoorOpenMethod = (EM_CFG_DOOR_OPEN_METHOD)dooropenmethod2.SelectedIndex;

            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuStartTime.dwHour = (uint)starttime3.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuStartTime.dwMinute = (uint)starttime3.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuStartTime.dwSecond = (uint)starttime3.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuEndTime.dwHour = (uint)endtime3.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuEndTime.dwMinute = (uint)endtime3.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].stuTime.stuEndTime.dwSecond = (uint)endtime3.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 2].emDoorOpenMethod = (EM_CFG_DOOR_OPEN_METHOD)dooropenmethod3.SelectedIndex;

            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuStartTime.dwHour = (uint)starttime4.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuStartTime.dwMinute = (uint)starttime4.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuStartTime.dwSecond = (uint)starttime4.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuEndTime.dwHour = (uint)endtime4.Value.Hour;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuEndTime.dwMinute = (uint)endtime4.Value.Minute;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].stuTime.stuEndTime.dwSecond = (uint)endtime4.Value.Second;
            m_TimeSections[cmb_WeekDay.SelectedIndex * 4 + 3].emDoorOpenMethod = (EM_CFG_DOOR_OPEN_METHOD)dooropenmethod4.SelectedIndex;

            DialogResult = DialogResult.OK;
            Close();
        }

        private void DoorOpenTimeSectionForm_Load(object sender, EventArgs e)
        {
            cmb_WeekDay.SelectedIndex = 0;
        }
    }
}
