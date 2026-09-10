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
    public partial class TimeScheduleForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;

        private readonly string CFG_CMD_ACCESSTIMESCHEDULE = "AccessTimeSchedule";
        private NET_CFG_ACCESS_TIMESCHEDULE_INFO timeSchedule = new NET_CFG_ACCESS_TIMESCHEDULE_INFO();

        public TimeScheduleForm()
        {
            InitializeComponent();
        }
        public TimeScheduleForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void TimeScheduleForm_Load(object sender, EventArgs e)
        {
            cmb_Index.Items.Clear();
            for (int i = 1; i < 129; i++)
            {
                cmb_Index.Items.Add(i);
            }
            cmb_Index.SelectedIndex = 0;

            if (GetConfig())
            {
                chb_ScheduleEnable.Checked = timeSchedule.bEnable;
                txt_CustomName.Text = timeSchedule.szName;
                cmb_WeekDay.SelectedIndex = 0;
            }
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()!");
                chb_ScheduleEnable.Checked = timeSchedule.bEnable;
                txt_CustomName.Text = timeSchedule.szName;
                cmb_WeekDay.SelectedIndex = 0;
            }
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            timeSchedule.bEnable = chb_ScheduleEnable.Checked;
            byte[] nameArray = Encoding.Default.GetBytes(txt_CustomName.Text);
            int nameCount = nameArray.Length < 128 ? nameArray.Length : 128;
            byte[] tempArray = new byte[128];
            for (int i = 0; i < nameCount; i++)
            {
                tempArray[i] = nameArray[i];
            }
            timeSchedule.szName = Encoding.Default.GetString(tempArray);

            try
            {
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nBeginHour = starttime1.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nBeginMin = starttime1.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nBeginSec = starttime1.Value.Second;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nEndHour = endtime1.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nEndMin = endtime1.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4].nEndSec = endtime1.Value.Second;

                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nBeginHour = starttime2.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nBeginMin = starttime2.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nBeginSec = starttime2.Value.Second;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nEndHour = endtime2.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nEndMin = endtime2.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1].nEndSec = endtime2.Value.Second;

                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nBeginHour = starttime3.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nBeginMin = starttime3.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nBeginSec = starttime3.Value.Second;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nEndHour = endtime3.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nEndMin = endtime3.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2].nEndSec = endtime3.Value.Second;

                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nBeginHour = starttime4.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nBeginMin = starttime4.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nBeginSec = starttime4.Value.Second;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nEndHour = endtime4.Value.Hour;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nEndMin = endtime4.Value.Minute;
                timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3].nEndSec = endtime4.Value.Second;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetConfig())
            {
                MessageBox.Show("Set successfully()!");
            }
        }

        private void cmb_WeekDay_SelectedIndexChanged(object sender, EventArgs e)
        {
            if(cmb_WeekDay.SelectedIndex == -1)
            {
                return;
            }
            try
            {
                var temp = timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4];
                starttime1.Value = new DateTime(2020, 1, 1, temp.nBeginHour, temp.nBeginMin, temp.nBeginSec);
                endtime1.Value = new DateTime(2020, 1, 1, temp.nEndHour, temp.nEndMin, temp.nEndSec);

                temp = timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 1];
                starttime2.Value = new DateTime(2020, 1, 1, temp.nBeginHour, temp.nBeginMin, temp.nBeginSec);
                endtime2.Value = new DateTime(2020, 1, 1, temp.nEndHour, temp.nEndMin, temp.nEndSec);

                temp = timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 2];
                starttime3.Value = new DateTime(2020, 1, 1, temp.nBeginHour, temp.nBeginMin, temp.nBeginSec);
                endtime3.Value = new DateTime(2020, 1, 1, temp.nEndHour, temp.nEndMin, temp.nEndSec);

                temp = timeSchedule.stuTime[cmb_WeekDay.SelectedIndex * 4 + 3];
                starttime4.Value = new DateTime(2020, 1, 1, temp.nBeginHour, temp.nBeginMin, temp.nBeginSec);
                endtime4.Value = new DateTime(2020, 1, 1, temp.nEndHour, temp.nEndMin, temp.nEndSec);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            
        }

        public bool GetConfig()
        {
            chb_ScheduleEnable.Checked = false;
            txt_CustomName.Text = "";
            cmb_WeekDay.SelectedIndex = -1;
            starttime1.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            endtime1.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            starttime2.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            endtime2.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            starttime3.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            endtime3.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            starttime4.Value = new DateTime(2020, 1, 1, 0, 0, 0);
            endtime4.Value = new DateTime(2020, 1, 1, 0, 0, 0);

            bool bRet = false;
            try
            {
                object objInfo = new object();
                bRet = NETClient.GetNewDevConfig(m_LoginID, cmb_Index.SelectedIndex, CFG_CMD_ACCESSTIMESCHEDULE, ref objInfo, typeof(NET_CFG_ACCESS_TIMESCHEDULE_INFO), 10000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
                else
                {
                    timeSchedule = (NET_CFG_ACCESS_TIMESCHEDULE_INFO)objInfo;
                }
            }
            catch (NETClientExcetion ex)
            {
                MessageBox.Show(ex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        public bool SetConfig()
        {
            bool bRet = false;
            try
            {
                object objInfo = timeSchedule;
                bRet = NETClient.SetNewDevConfig(m_LoginID, cmb_Index.SelectedIndex, CFG_CMD_ACCESSTIMESCHEDULE, objInfo, typeof(NET_CFG_ACCESS_TIMESCHEDULE_INFO), 10000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        private void cmb_Index_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (GetConfig())
            {
                chb_ScheduleEnable.Checked = timeSchedule.bEnable;
                txt_CustomName.Text = timeSchedule.szName;
                cmb_WeekDay.SelectedIndex = 0;
            }
        }
    }
}
