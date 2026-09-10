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
    public partial class DeviceTimeForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;

        private NET_TIME device_time = new NET_TIME();
        private NET_CFG_NTP_INFO cfg_ntp = new NET_CFG_NTP_INFO();
        private NET_AV_CFG_Locales cfg_dst = new NET_AV_CFG_Locales() { nStructSize = Marshal.SizeOf(typeof(NET_AV_CFG_Locales)) };

        private const string CFG_CMD_NTP = "NTP";
        private const string CFG_CMD_LOCALS = "Locales";

        public DeviceTimeForm()
        {
            InitializeComponent();
        }

        public DeviceTimeForm(IntPtr id)
        {
            InitializeComponent();
            m_LoginID = id;
        }

        private void btn_GetTime_Click(object sender, EventArgs e)
        {
            if (GetDeviceTime())
            {
                MessageBox.Show("Get Success()");
                dateTimePicker_devicetime.Value = device_time.ToDateTime();
            }
        }

        private void btn_SetTime_Click(object sender, EventArgs e)
        {
            device_time = NET_TIME.FromDateTime(dateTimePicker_devicetime.Value);
            if (SetDeviceTime())
            {
                MessageBox.Show("Set successfully()");
            }
        }

        private void btn_GetNTP_Click(object sender, EventArgs e)
        {
            if (GetNTPConfig())
            {
                MessageBox.Show("Get successfully()");

                chb_NTPenable.Checked = cfg_ntp.bEnable;
                txt_ip.Text = cfg_ntp.szAddress;
                txt_port.Text = cfg_ntp.nPort.ToString();
                txt_upgradeperiod.Text = cfg_ntp.nUpdatePeriod.ToString();
                cmb_timezone.SelectedIndex = (int)cfg_ntp.emTimeZoneType;
                txt_description.Text = cfg_ntp.szTimeZoneDesc;
            }
        }

        private void btn_SetNTP_Click(object sender, EventArgs e)
        {
            try
            {
                cfg_ntp.bEnable = chb_NTPenable.Checked;
                cfg_ntp.szAddress = txt_ip.Text;
                cfg_ntp.nPort = int.Parse(txt_port.Text);
                cfg_ntp.nUpdatePeriod = int.Parse(txt_upgradeperiod.Text);
                cfg_ntp.emTimeZoneType = (EM_CFG_TIME_ZONE_TYPE)cmb_timezone.SelectedIndex;
                cfg_ntp.szTimeZoneDesc = txt_description.Text;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetNTPConfig(cfg_ntp))
            {
                MessageBox.Show("Set successfully()");
            }
        }

        private void btn_GetDST_Click(object sender, EventArgs e)
        {
            try
            {
                ResetDSTShow();
                if (GetDSTConfig())
                {
                    MessageBox.Show("Get successfully()");

                    chb_DSTenable.Checked = cfg_dst.bDSTEnable;
                    cmb_start_year.SelectedIndex = cfg_dst.stuDstStart.nYear - 2000;
                    cmb_start_month.SelectedIndex = cfg_dst.stuDstStart.nMonth - 1;
                    cmb_start_hour.SelectedIndex = cfg_dst.stuDstStart.nHour;
                    cmb_start_minute.SelectedIndex = cfg_dst.stuDstStart.nMinute;
                    if (cfg_dst.stuDstStart.nWeek == 0)
                    {
                        cmb_DSTtype.SelectedIndex = 0;
                        cmb_start_day.Enabled = true;
                        cmb_start_day.SelectedIndex = cfg_dst.stuDstStart.nDay - 1;
                        cmb_startweek.SelectedIndex = -1;
                        cmb_startweek.Enabled = false;
                        cmb_startweekday.SelectedIndex = -1;
                        cmb_startweekday.Enabled = false;

                    }
                    else
                    {
                        cmb_DSTtype.SelectedIndex = 1;
                        cmb_start_day.SelectedIndex = -1;
                        cmb_start_day.Enabled = false;
                        cmb_startweek.Enabled = true;
                        cmb_startweek.SelectedIndex = cfg_dst.stuDstStart.nWeek == -1 ? 0 : cfg_dst.stuDstStart.nWeek;
                        cmb_startweekday.Enabled = true;
                        cmb_startweekday.SelectedIndex = cfg_dst.stuDstStart.nDay;
                    }

                    cmb_end_year.SelectedIndex = cfg_dst.stuDstEnd.nYear - 2000;
                    cmb_end_month.SelectedIndex = cfg_dst.stuDstEnd.nMonth - 1;
                    cmb_end_hour.SelectedIndex = cfg_dst.stuDstEnd.nHour;
                    cmb_end_minute.SelectedIndex = cfg_dst.stuDstEnd.nMinute;
                    if (cfg_dst.stuDstEnd.nWeek == 0)
                    {
                        cmb_end_day.Enabled = true;
                        cmb_end_day.SelectedIndex = cfg_dst.stuDstEnd.nDay - 1;
                        cmb_endweek.SelectedIndex = -1;
                        cmb_endweek.Enabled = false;
                        cmb_endweekday.SelectedIndex = -1;
                        cmb_endweekday.Enabled = false;
                    }
                    else
                    {
                        cmb_end_day.SelectedIndex = -1;
                        cmb_end_day.Enabled = false;
                        cmb_endweek.Enabled = true;
                        cmb_endweek.SelectedIndex = cfg_dst.stuDstEnd.nWeek == -1 ? 0 : cfg_dst.stuDstEnd.nWeek;
                        cmb_endweekday.Enabled = true;
                        cmb_endweekday.SelectedIndex = cfg_dst.stuDstEnd.nDay;
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            
        }

        private void btn_SetDST_Click(object sender, EventArgs e)
        {
            try
            {
                cfg_dst.bDSTEnable = chb_DSTenable.Checked;
                cfg_dst.stuDstStart.nYear = cmb_start_year.SelectedIndex + 2000;
                cfg_dst.stuDstStart.nMonth = cmb_start_month.SelectedIndex + 1;
                cfg_dst.stuDstStart.nHour = cmb_start_hour.SelectedIndex;
                cfg_dst.stuDstStart.nMinute = cmb_start_minute.SelectedIndex;

                cfg_dst.stuDstEnd.nYear = cmb_end_year.SelectedIndex + 2000;
                cfg_dst.stuDstEnd.nMonth = cmb_end_month.SelectedIndex + 1;
                cfg_dst.stuDstEnd.nHour = cmb_end_hour.SelectedIndex;
                cfg_dst.stuDstEnd.nMinute = cmb_end_minute.SelectedIndex;

                if (cmb_DSTtype.SelectedIndex == 0)
                {
                    cfg_dst.stuDstStart.nWeek = 0;
                    cfg_dst.stuDstStart.nDay = cmb_start_day.SelectedIndex + 1;

                    cfg_dst.stuDstEnd.nWeek = 0;
                    cfg_dst.stuDstEnd.nDay = cmb_end_day.SelectedIndex + 1;
                }
                else
                {
                    cfg_dst.stuDstStart.nWeek = cmb_startweek.SelectedIndex == 0 ? -1 : cmb_startweek.SelectedIndex;
                    cfg_dst.stuDstStart.nDay = cmb_startweekday.SelectedIndex;

                    cfg_dst.stuDstEnd.nWeek = cmb_endweek.SelectedIndex == 0 ? -1 : cmb_endweek.SelectedIndex;
                    cfg_dst.stuDstEnd.nDay = cmb_endweekday.SelectedIndex;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetDSTConfig(cfg_dst))
            {
                MessageBox.Show("Set successfully()");
            }
        }

        public bool GetDeviceTime()
        {
            bool ret = NETClient.QueryDeviceTime(m_LoginID, ref device_time, 5000);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
            }
            return ret;
        }

        public bool SetDeviceTime()
        {
            bool ret = NETClient.SetupDeviceTime(m_LoginID, device_time);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
            }
            return ret;
        }

        public bool GetNTPConfig()
        {
            bool bRet = false;
            try
            {
                object objTemp = new object();
                bRet = NETClient.GetNewDevConfig(m_LoginID, -1, CFG_CMD_NTP, ref objTemp, typeof(NET_CFG_NTP_INFO), 5000);
                if (bRet)
                {
                    cfg_ntp = (NET_CFG_NTP_INFO)objTemp;
                }
                else
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

        public bool SetNTPConfig(NET_CFG_NTP_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, -1, CFG_CMD_NTP, (object)cfg, typeof(NET_CFG_NTP_INFO), 5000);
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

        public bool GetDSTConfig()
        {
            bool bRet = false;
            try
            {
                cfg_dst = new NET_AV_CFG_Locales() { nStructSize = Marshal.SizeOf(typeof(NET_AV_CFG_Locales)) };
                cfg_dst.stuDstStart.nStructSize = Marshal.SizeOf(typeof(AV_CFG_DSTTime));
                cfg_dst.stuDstEnd.nStructSize = Marshal.SizeOf(typeof(AV_CFG_DSTTime));
                object objTemp = (object)cfg_dst;
                bRet = NETClient.GetNewDevConfig(m_LoginID, -1, CFG_CMD_LOCALS, ref objTemp, typeof(NET_AV_CFG_Locales), 5000);
                if (bRet)
                {
                    cfg_dst = (NET_AV_CFG_Locales)objTemp;
                }
                else
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

        public bool SetDSTConfig(NET_AV_CFG_Locales cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, -1, CFG_CMD_LOCALS, (object)cfg, typeof(NET_AV_CFG_Locales), 5000);
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

        private void DeviceTimeForm_Load(object sender, EventArgs e)
        {
            try
            {
                cmb_start_year.Items.Clear();
                cmb_start_month.Items.Clear();
                cmb_start_hour.Items.Clear();
                cmb_start_minute.Items.Clear();
                for (int i = 2000; i <= 2038; i++)
                {
                    cmb_start_year.Items.Add(i);
                }
                for (int i = 1; i <= 12; i++)
                {
                    cmb_start_month.Items.Add(i);
                }
                for (int i = 0; i < 24; i++)
                {
                    cmb_start_hour.Items.Add(i);
                }
                for (int i = 0; i < 60; i++)
                {
                    cmb_start_minute.Items.Add(i);
                }
                cmb_start_day.Items.Clear();
                for (int i = 1; i <= 31; i++)
                {
                    cmb_start_day.Items.Add(i);
                }

                cmb_end_year.Items.Clear();
                cmb_end_month.Items.Clear();
                cmb_end_hour.Items.Clear();
                cmb_end_minute.Items.Clear();
                for (int i = 2000; i <= 2038; i++)
                {
                    cmb_end_year.Items.Add(i);
                }
                for (int i = 1; i <= 12; i++)
                {
                    cmb_end_month.Items.Add(i);
                }
                for (int i = 0; i < 24; i++)
                {
                    cmb_end_hour.Items.Add(i);
                }
                for (int i = 0; i < 60; i++)
                {
                    cmb_end_minute.Items.Add(i);
                }
                cmb_end_day.Items.Clear();
                for (int i = 1; i <= 31; i++)
                {
                    cmb_end_day.Items.Add(i);
                }

                if (GetDeviceTime())
                {
                    dateTimePicker_devicetime.Value = device_time.ToDateTime();
                }

                if (GetNTPConfig())
                {
                    chb_NTPenable.Checked = cfg_ntp.bEnable;
                    txt_ip.Text = cfg_ntp.szAddress;
                    txt_port.Text = cfg_ntp.nPort.ToString();
                    txt_upgradeperiod.Text = cfg_ntp.nUpdatePeriod.ToString();
                    cmb_timezone.SelectedIndex = (int)cfg_ntp.emTimeZoneType;
                    txt_description.Text = cfg_ntp.szTimeZoneDesc;
                }

                if (GetDSTConfig())
                {
                    chb_DSTenable.Checked = cfg_dst.bDSTEnable;
                    cmb_start_year.SelectedIndex = cfg_dst.stuDstStart.nYear - 2000;
                    cmb_start_month.SelectedIndex = cfg_dst.stuDstStart.nMonth - 1;
                    cmb_start_hour.SelectedIndex = cfg_dst.stuDstStart.nHour;
                    cmb_start_minute.SelectedIndex = cfg_dst.stuDstStart.nMinute;
                    if (cfg_dst.stuDstStart.nWeek == 0)
                    {
                        cmb_DSTtype.SelectedIndex = 0;
                        cmb_start_day.Enabled = true;
                        cmb_start_day.SelectedIndex = cfg_dst.stuDstStart.nDay - 1;
                        cmb_startweek.SelectedIndex = -1;
                        cmb_startweek.Enabled = false;
                        cmb_startweekday.SelectedIndex = -1;
                        cmb_startweekday.Enabled = false;

                    }
                    else
                    {
                        cmb_DSTtype.SelectedIndex = 1;
                        cmb_start_day.SelectedIndex = -1;
                        cmb_start_day.Enabled = false;
                        cmb_startweek.Enabled = true;
                        cmb_startweek.SelectedIndex = cfg_dst.stuDstStart.nWeek == -1 ? 0 : cfg_dst.stuDstStart.nWeek;
                        cmb_startweekday.Enabled = true;
                        cmb_startweekday.SelectedIndex = cfg_dst.stuDstStart.nDay;
                    }

                    cmb_end_year.SelectedIndex = cfg_dst.stuDstEnd.nYear - 2000;
                    cmb_end_month.SelectedIndex = cfg_dst.stuDstEnd.nMonth - 1;
                    cmb_end_hour.SelectedIndex = cfg_dst.stuDstEnd.nHour;
                    cmb_end_minute.SelectedIndex = cfg_dst.stuDstEnd.nMinute;
                    if (cfg_dst.stuDstEnd.nWeek == 0)
                    {
                        cmb_end_day.Enabled = true;
                        cmb_end_day.SelectedIndex = cfg_dst.stuDstEnd.nDay - 1;
                        cmb_endweek.SelectedIndex = -1;
                        cmb_endweek.Enabled = false;
                        cmb_endweekday.SelectedIndex = -1;
                        cmb_endweekday.Enabled = false;
                    }
                    else
                    {
                        cmb_end_day.SelectedIndex = -1;
                        cmb_end_day.Enabled = false;
                        cmb_endweek.Enabled = true;
                        cmb_endweek.SelectedIndex = cfg_dst.stuDstEnd.nWeek == -1 ? 0 : cfg_dst.stuDstEnd.nWeek;
                        cmb_endweekday.Enabled = true;
                        cmb_endweekday.SelectedIndex = cfg_dst.stuDstEnd.nDay;
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message); 
            }
        }

        private void cmb_start_month_SelectedIndexChanged(object sender, EventArgs e)
        {
            int monthdays = 0;
            int month = cmb_start_month.SelectedIndex + 1;
            if(month==1|| month == 3 || month == 5 || month == 7 || month == 8 || month == 10 || month == 12)
            {
                monthdays = 31;
            }
            else if (month == 2)
            {
                monthdays = 29;
            }
            else
            {
                monthdays = 30;
            }
            cmb_start_day.Items.Clear();
            for (int i = 1; i <= monthdays; i++)
            {
                cmb_start_day.Items.Add(i);
            }
        }

        private void cmb_end_month_SelectedIndexChanged(object sender, EventArgs e)
        {
            int monthdays = 0;
            int month = cmb_end_month.SelectedIndex + 1;
            if (month == 1 || month == 3 || month == 5 || month == 7 || month == 8 || month == 10 || month == 12)
            {
                monthdays = 31;
            }
            else if (month == 2)
            {
                monthdays = 29;
            }
            else
            {
                monthdays = 30;
            }
            cmb_end_day.Items.Clear();
            for (int i = 1; i <= monthdays; i++)
            {
                cmb_end_day.Items.Add(i);
            }
        }

        private void cmb_DSTtype_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (cmb_DSTtype.SelectedIndex == 0)
            {
                cmb_start_day.Enabled = true;
                cmb_start_day.SelectedIndex = 0;
                cmb_startweek.SelectedIndex = -1;
                cmb_startweek.Enabled = false;
                cmb_startweekday.SelectedIndex = -1;
                cmb_startweekday.Enabled = false;

                cmb_end_day.Enabled = true;
                cmb_end_day.SelectedIndex = 0;
                cmb_endweek.SelectedIndex = -1;
                cmb_endweek.Enabled = false;
                cmb_endweekday.SelectedIndex = -1;
                cmb_endweekday.Enabled = false;
            }
            else
            {
                cmb_start_day.SelectedIndex = -1;
                cmb_start_day.Enabled = false;
                cmb_startweek.Enabled = true;
                cmb_startweek.SelectedIndex = 0;
                cmb_startweekday.Enabled = true;
                cmb_startweekday.SelectedIndex = 0;

                cmb_end_day.SelectedIndex = -1;
                cmb_end_day.Enabled = false;
                cmb_endweek.Enabled = true;
                cmb_endweek.SelectedIndex = 0;
                cmb_endweekday.Enabled = true;
                cmb_endweekday.SelectedIndex = 0;
            }
        }

        private void ResetDSTShow()
        {
            try
            {
                chb_DSTenable.Checked = false;
                cmb_DSTtype.SelectedIndex = -1;
                cmb_start_year.SelectedIndex = -1;
                cmb_start_month.SelectedIndex = -1;
                cmb_start_day.SelectedIndex = -1;
                cmb_start_hour.SelectedIndex = -1;
                cmb_start_minute.SelectedIndex = -1;
                cmb_startweek.SelectedIndex = -1;
                cmb_startweekday.SelectedIndex = -1;
                cmb_end_year.SelectedIndex = -1;
                cmb_end_month.SelectedIndex = -1;
                cmb_end_day.SelectedIndex = -1;
                cmb_end_hour.SelectedIndex = -1;
                cmb_end_minute.SelectedIndex = -1;
                cmb_endweek.SelectedIndex = -1;
                cmb_endweekday.SelectedIndex = -1;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

    }
}
