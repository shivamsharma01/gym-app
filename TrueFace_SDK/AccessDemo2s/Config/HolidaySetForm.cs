using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class HolidaySetForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO m_SpecialdayGroupInfo = new NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO();
        private NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO m_ScheduleInfo = new NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO(); 

        public HolidaySetForm()
        {
            InitializeComponent();
        }
        public HolidaySetForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void HolidaySetForm_Load(object sender, EventArgs e)
        {
            cmb_Index.Items.Clear();
            for (int i = 0; i < 128; i++)
            {
                cmb_Index.Items.Add(i);
            }
            cmb_Index.SelectedIndex = 0;

            cmb_ScheduleGroup.Items.Clear();
            for (int i = 0; i < 128; i++)
            {
                cmb_ScheduleGroup.Items.Add(i);
            }
            cmb_ScheduleGroup.SelectedIndex = 0;

        }

        private void btn_GetGroup_Click(object sender, EventArgs e)
        {
            if (GetGroupInfo())
            {
                MessageBox.Show("Get successfully()!");
            }
        }

        private void btn_SetGroup_Click(object sender, EventArgs e)
        {
            if (SetGroupInfo())
            {
                MessageBox.Show("Set successfully()!");
            }

        }

        private void cmb_Index_SelectedIndexChanged(object sender, EventArgs e)
        {
            GetGroupInfo();
        }

        private bool GetGroupInfo()
        {
            NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO stuIn = new NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO));
            object obj = stuIn;
            bool ret = NETClient.GetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.SPECIALDAY_GROUP, cmb_Index.SelectedIndex, ref obj, typeof(NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO), 5000);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return false;
            }
            m_SpecialdayGroupInfo = (NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO)obj;

            chb_Enable.Checked = m_SpecialdayGroupInfo.bGroupEnable;
            txt_Name.Text = m_SpecialdayGroupInfo.szGroupName;
            if (listView_Group != null)
            {
                listView_Group.Items.Clear();
                for (int i = 0; i < m_SpecialdayGroupInfo.nSpeciaday; i++)
                {
                    var listitem = new ListViewItem();
                    listitem.Text = (i + 1).ToString();
                    listitem.SubItems.Add(m_SpecialdayGroupInfo.stuSpeciaday[i].szDayName);
                    listitem.SubItems.Add(m_SpecialdayGroupInfo.stuSpeciaday[i].stuStartTime.ToString());
                    listitem.SubItems.Add(m_SpecialdayGroupInfo.stuSpeciaday[i].stuEndTime.ToString());

                    listView_Group.BeginUpdate();
                    listView_Group.Items.Add(listitem);
                    listView_Group.EndUpdate();
                }
            }
                
            return true;
        }

        private bool SetGroupInfo()
        {
            m_SpecialdayGroupInfo.bGroupEnable = chb_Enable.Checked;
            byte[] nameArray = Encoding.Default.GetBytes(txt_Name.Text);
            int nameCount = nameArray.Length < 32 ? nameArray.Length : 32;
            byte[] tempArray = new byte[32];
            for (int i = 0; i < nameCount; i++)
            {
                tempArray[i] = nameArray[i];
            }
            m_SpecialdayGroupInfo.szGroupName = Encoding.Default.GetString(tempArray);

            object obj = m_SpecialdayGroupInfo;
            //  0-127
            bool ret = NETClient.SetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.SPECIALDAY_GROUP, cmb_Index.SelectedIndex, obj, typeof(NET_CFG_ACCESSCTL_SPECIALDAY_GROUP_INFO), 5000);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return false;
            }
            return true;
        }

        private void btn_AddGroup_Click(object sender, EventArgs e)
        {
            if (m_SpecialdayGroupInfo.nSpeciaday >= 16)
            {
                MessageBox.Show("Is full()");
                return;
            }

            SpecialDayInfoForm dayInfo = new SpecialDayInfoForm(m_SpecialdayGroupInfo.stuSpeciaday[m_SpecialdayGroupInfo.nSpeciaday]);
            dayInfo.ShowDialog();
            if(dayInfo.DialogResult == DialogResult.OK)
            {
                m_SpecialdayGroupInfo.stuSpeciaday[m_SpecialdayGroupInfo.nSpeciaday] = dayInfo.m_SpecialDayInfo;
                m_SpecialdayGroupInfo.nSpeciaday++;
            }
            dayInfo.Dispose();
            SetGroupInfo();
            GetGroupInfo();
        }

        private void btn_ModifyGroup_Click(object sender, EventArgs e)
        {
            try
            {
                if (listView_Group.SelectedItems.Count == 0)
                {
                    MessageBox.Show("Please select one data!(！)");
                    return;
                }
                string str_No = listView_Group.SelectedItems[0].SubItems[0].Text;
                int info_No = int.Parse(str_No) - 1;
                SpecialDayInfoForm dayInfo = new SpecialDayInfoForm(m_SpecialdayGroupInfo.stuSpeciaday[info_No]);
                dayInfo.ShowDialog();
                if (dayInfo.DialogResult == DialogResult.OK)
                {
                    m_SpecialdayGroupInfo.stuSpeciaday[info_No] = dayInfo.m_SpecialDayInfo;
                }
                dayInfo.Dispose();
                SetGroupInfo();
                GetGroupInfo();
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void btn_RemoveGroup_Click(object sender, EventArgs e)
        {
            try
            {
                if (listView_Group.SelectedItems.Count == 0)
                {
                    MessageBox.Show("Please select one data!(！)");
                    return;
                }
                string str_No = listView_Group.SelectedItems[0].SubItems[0].Text;
                int info_No = int.Parse(str_No) - 1;
                for (int i = info_No; i < m_SpecialdayGroupInfo.nSpeciaday - 1; i++)
                {
                    m_SpecialdayGroupInfo.stuSpeciaday[info_No] = m_SpecialdayGroupInfo.stuSpeciaday[info_No + 1];
                }
                m_SpecialdayGroupInfo.stuSpeciaday[m_SpecialdayGroupInfo.nSpeciaday - 1] = new NET_ACCESSCTL_SPECIALDAY_INFO();
                m_SpecialdayGroupInfo.nSpeciaday--;
                SetGroupInfo();
                GetGroupInfo();
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void cmb_ScheduleGroup_SelectedIndexChanged(object sender, EventArgs e)
        {
            GetScheduleInfo();
        }

        private void btn_GetSchedule_Click(object sender, EventArgs e)
        {
            if (GetScheduleInfo())
            {
                MessageBox.Show("Get successfully()!");
            }
        }

        private void btn_SetSchedule_Click(object sender, EventArgs e)
        {
            if (SetScheduleInfo())
            {
                MessageBox.Show("Set successfully()!");
            }
        }

        private bool GetScheduleInfo()
        {
            NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO stuIn = new NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO));
            object obj = stuIn;
            bool ret = NETClient.GetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.SPECIALDAYS_SCHEDULE, cmb_ScheduleGroup.SelectedIndex, ref obj, typeof(NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO), 5000);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return false;
            }
            m_ScheduleInfo = (NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO)obj;

            txt_ScheduleName.Text = m_ScheduleInfo.szSchduleName;
            chb_ScheduleEnable.Checked = m_ScheduleInfo.bSchdule;
            txt_GroupNum.Text = m_ScheduleInfo.nGroupNo.ToString();

            var temp = m_ScheduleInfo.stuTimeSection[0];
            starttime1.Value = new DateTime(2020, 1, 1, temp.iBeginHour, temp.iBeginMin, temp.iBeginSec);
            endtime1.Value = new DateTime(2020, 1, 1, temp.iEndHour, temp.iEndMin, temp.iEndSec);

            temp = m_ScheduleInfo.stuTimeSection[1];
            starttime2.Value = new DateTime(2020, 1, 1, temp.iBeginHour, temp.iBeginMin, temp.iBeginSec);
            endtime2.Value = new DateTime(2020, 1, 1, temp.iEndHour, temp.iEndMin, temp.iEndSec);

            temp = m_ScheduleInfo.stuTimeSection[2];
            starttime3.Value = new DateTime(2020, 1, 1, temp.iBeginHour, temp.iBeginMin, temp.iBeginSec);
            endtime3.Value = new DateTime(2020, 1, 1, temp.iEndHour, temp.iEndMin, temp.iEndSec);

            temp = m_ScheduleInfo.stuTimeSection[3];
            starttime4.Value = new DateTime(2020, 1, 1, temp.iBeginHour, temp.iBeginMin, temp.iBeginSec);
            endtime4.Value = new DateTime(2020, 1, 1, temp.iEndHour, temp.iEndMin, temp.iEndSec);

            return true;
        }

        private bool SetScheduleInfo()
        {
            try
            {
                byte[] nameArray = Encoding.Default.GetBytes(txt_ScheduleName.Text);
                int nameCount = nameArray.Length < 64 ? nameArray.Length : 64;
                byte[] tempArray = new byte[64];
                for (int i = 0; i < nameCount; i++)
                {
                    tempArray[i] = nameArray[i];
                }
                m_ScheduleInfo.szSchduleName = Encoding.Default.GetString(tempArray);

                m_ScheduleInfo.bSchdule = chb_ScheduleEnable.Checked;
                m_ScheduleInfo.nGroupNo = int.Parse(txt_GroupNum.Text);

                m_ScheduleInfo.stuTimeSection[0].iBeginHour = starttime1.Value.Hour;
                m_ScheduleInfo.stuTimeSection[0].iBeginMin = starttime1.Value.Minute;
                m_ScheduleInfo.stuTimeSection[0].iBeginSec = starttime1.Value.Second;
                m_ScheduleInfo.stuTimeSection[0].iEndHour = endtime1.Value.Hour;
                m_ScheduleInfo.stuTimeSection[0].iEndMin = endtime1.Value.Minute;
                m_ScheduleInfo.stuTimeSection[0].iEndSec = endtime1.Value.Second;

                m_ScheduleInfo.stuTimeSection[1].iBeginHour = starttime2.Value.Hour;
                m_ScheduleInfo.stuTimeSection[1].iBeginMin = starttime2.Value.Minute;
                m_ScheduleInfo.stuTimeSection[1].iBeginSec = starttime2.Value.Second;
                m_ScheduleInfo.stuTimeSection[1].iEndHour = endtime2.Value.Hour;
                m_ScheduleInfo.stuTimeSection[1].iEndMin = endtime2.Value.Minute;
                m_ScheduleInfo.stuTimeSection[1].iEndSec = endtime2.Value.Second;

                m_ScheduleInfo.stuTimeSection[2].iBeginHour = starttime3.Value.Hour;
                m_ScheduleInfo.stuTimeSection[2].iBeginMin = starttime3.Value.Minute;
                m_ScheduleInfo.stuTimeSection[2].iBeginSec = starttime3.Value.Second;
                m_ScheduleInfo.stuTimeSection[2].iEndHour = endtime3.Value.Hour;
                m_ScheduleInfo.stuTimeSection[2].iEndMin = endtime3.Value.Minute;
                m_ScheduleInfo.stuTimeSection[2].iEndSec = endtime3.Value.Second;

                m_ScheduleInfo.stuTimeSection[3].iBeginHour = starttime4.Value.Hour;
                m_ScheduleInfo.stuTimeSection[3].iBeginMin = starttime4.Value.Minute;
                m_ScheduleInfo.stuTimeSection[3].iBeginSec = starttime4.Value.Second;
                m_ScheduleInfo.stuTimeSection[3].iEndHour = endtime4.Value.Hour;
                m_ScheduleInfo.stuTimeSection[3].iEndMin = endtime4.Value.Minute;
                m_ScheduleInfo.stuTimeSection[3].iEndSec = endtime4.Value.Second;

                object obj = m_ScheduleInfo;
                //  0-127
                bool ret = NETClient.SetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.SPECIALDAYS_SCHEDULE, cmb_ScheduleGroup.SelectedIndex, obj, typeof(NET_CFG_ACCESSCTL_SPECIALDAYS_SCHEDULE_INFO), 5000);
                if (!ret)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return false;
                }
                return true;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return false;
            }
        }

        private void btn_Doors_Click(object sender, EventArgs e)
        {
            if (null == m_ScheduleInfo.nDoors)
            {
                m_ScheduleInfo.nDoors = new int[64];
            }
            List<int> doors = new List<int>();
            for (int i = 0; i < m_ScheduleInfo.nDoorNum; i++)
            {
                doors.Add(m_ScheduleInfo.nDoors[i]);
            }
            DoorSelectForm doorForm = new DoorSelectForm(64, doors);
            doorForm.ShowDialog();
            if (doorForm.DialogResult == DialogResult.OK)
            {
                var result = doorForm.SelectDoorsList;
                if (result.Count > 0)
                {
                    for (int i = 0; i < result.Count; i++)
                    {
                        m_ScheduleInfo.nDoors[i] = result[i];
                    }
                }
                else
                {
                    m_ScheduleInfo.nDoors = new int[64];
                }
                m_ScheduleInfo.nDoorNum = result.Count;
            }
            doorForm.Dispose();
        }
    }
}
