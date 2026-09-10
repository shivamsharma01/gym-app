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
    public partial class GeneralConfigForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private int m_Channel = 0;
        private NET_CFG_ACCESS_EVENT_INFO cfg = new NET_CFG_ACCESS_EVENT_INFO();

        public GeneralConfigForm()
        {
            InitializeComponent();
        }

        public GeneralConfigForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_Channel = channel;
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()!");
            }

            cmb_OpenMethod.SelectedIndex = (int)cfg.emDoorOpenMethod;
            txt_UnlockHold.Text = cfg.nUnlockHoldInterval.ToString();
            txt_CloseTimeout.Text = cfg.nCloseTimeout.ToString();
            txt_HolidayTime.Text = cfg.nHolidayTimeRecoNo.ToString();

            txt_OpenTime.Text = cfg.nOpenAlwaysTimeIndex.ToString();
            txt_CloseTime.Text = cfg.nCloseAlwaysTimeIndex.ToString();
            txt_RemoteTime.Text = cfg.stuAutoRemoteCheck.nTimeSechdule.ToString();
            chb_RemoteEnable.Checked = cfg.stuAutoRemoteCheck.bEnable;

            chb_Repear.Checked = cfg.abRepeatEnterAlarmEnable == 1 && cfg.bRepeatEnterAlarm;
            chb_Unclose.Checked = cfg.abDoorNotClosedAlarmEnable == 1 && cfg.bDoorNotClosedAlarmEnable;
            chb_Duress.Checked = cfg.abDuressAlarmEnable == 1 && cfg.bDuressAlarmEnable;
            chb_Sensor.Checked = cfg.abSensorEnable == 1 && cfg.bSensorEnable;
            chb_Break.Checked = cfg.abBreakInAlarmEnable == 1 && cfg.bBreakInAlarmEnable;
            chb_MaliciousSwip.Checked = cfg.bUnAuthorizedMaliciousSwipEnable;
            chb_CustomPwd.Checked = cfg.bCustomPasswordEnable;
            cmb_AccessState.SelectedIndex = (int)cfg.emState;

            btn_OpenTime.Enabled = true;
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            cfg.emDoorOpenMethod = (EM_CFG_DOOR_OPEN_METHOD)cmb_OpenMethod.SelectedIndex;
            try
            {
                cfg.nUnlockHoldInterval = int.Parse(txt_UnlockHold.Text);
                cfg.nCloseTimeout = int.Parse(txt_CloseTimeout.Text);
                cfg.nHolidayTimeRecoNo = int.Parse(txt_HolidayTime.Text);   // ，0。 corresponding CFG_CMD_ACCESSTIMESCHEDULE Channel Number

                cfg.nOpenAlwaysTimeIndex = int.Parse(txt_OpenTime.Text);
                cfg.nCloseAlwaysTimeIndex = int.Parse(txt_CloseTime.Text);
                cfg.stuAutoRemoteCheck.nTimeSechdule = int.Parse(txt_RemoteTime.Text);
                cfg.stuAutoRemoteCheck.bEnable = chb_RemoteEnable.Checked;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            cfg.bRepeatEnterAlarm = chb_Repear.Checked;
            cfg.bDoorNotClosedAlarmEnable = chb_Unclose.Checked;
            cfg.bDuressAlarmEnable = chb_Duress.Checked;
            cfg.bSensorEnable = chb_Sensor.Checked;
            cfg.bBreakInAlarmEnable = chb_Break.Checked;
            cfg.bUnAuthorizedMaliciousSwipEnable = chb_MaliciousSwip.Checked;
            cfg.bCustomPasswordEnable = chb_CustomPwd.Checked;
            cfg.emState = (EM_CFG_ACCESS_STATE)cmb_AccessState.SelectedIndex;

            if (SetConfig(cfg))
            {
                MessageBox.Show("Set successfully()!");
            }
        }

        private void btn_OpenTime_Click(object sender, EventArgs e)
        {
            if(cfg.stuDoorTimeSection == null)
            {
                return;
            }
            DoorOpenTimeSectionForm doorSection = new DoorOpenTimeSectionForm(cfg.stuDoorTimeSection);
            doorSection.ShowDialog();
            if (doorSection.DialogResult == DialogResult.OK)
            {
                cfg.stuDoorTimeSection = doorSection.m_TimeSections;
            }
            doorSection.Dispose();
        }

        public bool GetConfig()
        {
            bool bRet = false;
            try
            {
                object objTemp = new object();
                bRet = NETClient.GetNewDevConfig(m_LoginID, cmb_Channel.SelectedIndex, "AccessControl", ref objTemp, typeof(NET_CFG_ACCESS_EVENT_INFO), 5000);
                if (bRet)
                {
                    cfg = (NET_CFG_ACCESS_EVENT_INFO)objTemp;
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
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

        public bool SetConfig(NET_CFG_ACCESS_EVENT_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, cmb_Channel.SelectedIndex, "AccessControl", (object)cfg, typeof(NET_CFG_ACCESS_EVENT_INFO), 5000);
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

        private void GeneralConfigForm_Load(object sender, EventArgs e)
        {
            cmb_Channel.Items.Clear();
            if (m_Channel > 0)
            {
                for (int i = 1; i <= m_Channel; i++)
                {
                    cmb_Channel.Items.Add(i);
                }
                cmb_Channel.SelectedIndex = 0;
            }

            if (GetConfig())
            {
                cmb_OpenMethod.SelectedIndex = (int)cfg.emDoorOpenMethod;
                txt_UnlockHold.Text = cfg.nUnlockHoldInterval.ToString();
                txt_CloseTimeout.Text = cfg.nCloseTimeout.ToString();
                txt_HolidayTime.Text = cfg.nHolidayTimeRecoNo.ToString();

                txt_OpenTime.Text = cfg.nOpenAlwaysTimeIndex.ToString();
                txt_CloseTime.Text = cfg.nCloseAlwaysTimeIndex.ToString();
                txt_RemoteTime.Text = cfg.stuAutoRemoteCheck.nTimeSechdule.ToString();
                chb_RemoteEnable.Checked = cfg.stuAutoRemoteCheck.bEnable;

                chb_Repear.Checked = cfg.abRepeatEnterAlarmEnable == 1 && cfg.bRepeatEnterAlarm;
                chb_Unclose.Checked = cfg.abDoorNotClosedAlarmEnable == 1 && cfg.bDoorNotClosedAlarmEnable;
                chb_Duress.Checked = cfg.abDuressAlarmEnable == 1 && cfg.bDuressAlarmEnable;
                chb_Sensor.Checked = cfg.abSensorEnable == 1 && cfg.bSensorEnable;
                chb_Break.Checked = cfg.abBreakInAlarmEnable == 1 && cfg.bBreakInAlarmEnable;
                chb_MaliciousSwip.Checked = cfg.bUnAuthorizedMaliciousSwipEnable;
                chb_CustomPwd.Checked = cfg.bCustomPasswordEnable;
                cmb_AccessState.SelectedIndex = (int)cfg.emState;

                btn_OpenTime.Enabled = true;
            }
        }

        private void btn_Config_Click(object sender, EventArgs e)
        {
            TimeScheduleForm timeSchedule = new TimeScheduleForm(m_LoginID);
            timeSchedule.ShowDialog();
            timeSchedule.Dispose();
        }

        private void btn_Manager_Click(object sender, EventArgs e)
        {
            HolidaySetForm holidaySet = new HolidaySetForm(m_LoginID);
            holidaySet.ShowDialog();
            holidaySet.Dispose();
        }
    }
}
