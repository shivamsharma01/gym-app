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
    public partial class RecordQueryForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private IntPtr m_FindDoorRecordID = IntPtr.Zero;
        private IntPtr m_FindAlarmRecordID = IntPtr.Zero;
        private IntPtr m_FindLogID = IntPtr.Zero;
        private int m_LogNum = 0;

        public RecordQueryForm()
        {
            InitializeComponent();
        }

        public RecordQueryForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void btn_StartQueryDoorRecord_Click(object sender, EventArgs e)
        {
            m_LogNum = 0;
            if (IntPtr.Zero == m_FindDoorRecordID)
            {
                NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX condition = new NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX();
                condition.dwSize = (uint)Marshal.SizeOf(typeof(NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX));
                condition.bTimeEnable = true;
                condition.stStartTime = NET_TIME.FromDateTime(dateTimePicker_DoorStart.Value);
                condition.stEndTime = NET_TIME.FromDateTime(dateTimePicker_DoorEnd.Value);
                object obj = condition;

                bool ret = NETClient.FindRecord(m_LoginID, EM_NET_RECORD_TYPE.ACCESSCTLCARDREC_EX, obj, typeof(NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX), ref m_FindDoorRecordID, 10000);
                if (!ret)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
                btn_StartQueryDoorRecord.Text = "StopQuery()";
                btn_NextDoorRecord.Enabled = true;
                btn_GetDoorRecordCount.Enabled = true;
                dateTimePicker_DoorStart.Enabled = false;
                dateTimePicker_DoorEnd.Enabled = false;
            }
            else
            {
                NETClient.FindRecordClose(m_FindDoorRecordID);
                m_FindDoorRecordID = IntPtr.Zero;
                btn_StartQueryDoorRecord.Text = "StartQuery()";
                btn_NextDoorRecord.Enabled = false;
                btn_GetDoorRecordCount.Enabled = false;
                dateTimePicker_DoorStart.Enabled = true;
                dateTimePicker_DoorEnd.Enabled = true;
                txt_DoorRecordCount.Text = "";
                listView_DoorRecord.Items.Clear();
            }
        }

        private void btn_GetDoorRecordCount_Click(object sender, EventArgs e)
        {
            if(IntPtr.Zero == m_FindDoorRecordID)
            {
                return;
            }

            int nCount = 0;
            try
            {
                if (NETClient.QueryRecordCount(m_FindDoorRecordID, ref nCount, 3000))
                {
                    txt_DoorRecordCount.Text = nCount.ToString();
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }
            catch (NETClientExcetion ex)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }
        }

        private void btn_NextDoorRecord_Click(object sender, EventArgs e)
        {
            listView_DoorRecord.Items.Clear();
            int max = 20;
            int retNum = 0;
            List<object> ls = new List<object>();
            for (int i = 0; i < max; i++)
            {
                NET_RECORDSET_ACCESS_CTL_CARDREC cardrec = new NET_RECORDSET_ACCESS_CTL_CARDREC();
                cardrec.dwSize = (uint)Marshal.SizeOf(typeof(NET_RECORDSET_ACCESS_CTL_CARDREC));
                ls.Add(cardrec);
            }
            NETClient.FindNextRecord(m_FindDoorRecordID, max, ref retNum, ref ls, typeof(NET_RECORDSET_ACCESS_CTL_CARDREC), 10000);
            BeginInvoke(new Action(() =>
            {
                foreach (var item in ls)
                {
                    NET_RECORDSET_ACCESS_CTL_CARDREC info = (NET_RECORDSET_ACCESS_CTL_CARDREC)item;
                    var listitem = new ListViewItem();
                    listitem.Text = info.nRecNo.ToString();
                    DateTime tmpDateTime = new DateTime((int)info.stuTime.dwYear, (int)info.stuTime.dwMonth, (int)info.stuTime.dwDay,
                        (int)info.stuTime.dwHour, (int)info.stuTime.dwMinute, (int)info.stuTime.dwSecond);
                    DateTime retDateTime = Common.Common.GetZoneTimeByUTCTime(tmpDateTime);

                    listitem.SubItems.Add(retDateTime.ToString());
                    listitem.SubItems.Add(info.szCardNo);
                    listitem.SubItems.Add(info.bStatus.ToString());
                    listitem.SubItems.Add(info.nDoor.ToString());
                    listitem.SubItems.Add(info.emMethod.ToString());
                    if (listView_DoorRecord != null)
                    {
                        listView_DoorRecord.BeginUpdate();
                        listView_DoorRecord.Items.Add(listitem);
                        listView_DoorRecord.EndUpdate();
                    }
                }
            }));
        }

        private void btn_StartQueryAlarmRecord_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_FindAlarmRecordID)
            {
                NET_FIND_NET_RECORD_ACCESS_ALARMRECORD_INFO_CONDITION condition = new NET_FIND_NET_RECORD_ACCESS_ALARMRECORD_INFO_CONDITION();
                condition.dwSize = (uint)Marshal.SizeOf(typeof(NET_FIND_NET_RECORD_ACCESS_ALARMRECORD_INFO_CONDITION));
                condition.stStartTime = NET_TIME.FromDateTime(dateTimePicker_DoorStart.Value);
                condition.stEndTime = NET_TIME.FromDateTime(dateTimePicker_DoorEnd.Value);
                object obj = condition;

                bool ret = NETClient.FindRecord(m_LoginID, EM_NET_RECORD_TYPE.ACCESS_ALARMRECORD, obj, typeof(NET_FIND_NET_RECORD_ACCESS_ALARMRECORD_INFO_CONDITION), ref m_FindAlarmRecordID, 10000);
                if (!ret)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
                btn_StartQueryAlarmRecord.Text = "StopQuery()";
                btn_NextAlarmRecord.Enabled = true;
                btn_GetAlarmRecordCount.Enabled = true;
                dateTimePicker_AlarmStart.Enabled = false;
                dateTimePicker_AlarmEnd.Enabled = false;
            }
            else
            {
                NETClient.FindRecordClose(m_FindAlarmRecordID);
                m_FindAlarmRecordID = IntPtr.Zero;
                btn_StartQueryAlarmRecord.Text = "StartQuery()";
                btn_NextAlarmRecord.Enabled = false;
                btn_GetAlarmRecordCount.Enabled = false;
                dateTimePicker_AlarmStart.Enabled = true;
                dateTimePicker_AlarmEnd.Enabled = true;
                txt_AlarmRecordCount.Text = "";
                listView_AlarmRecord.Items.Clear();
            }
        }

        private void btn_GetAlarmRecordCount_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_FindAlarmRecordID)
            {
                return;
            }

            int nCount = 0;
            try
            {
                if (NETClient.QueryRecordCount(m_FindAlarmRecordID, ref nCount, 3000))
                {
                    txt_AlarmRecordCount.Text = nCount.ToString();
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
            }
            catch (NETClientExcetion ex)
            {
                MessageBox.Show(NETClient.GetLastError());
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void btn_NextAlarmRecord_Click(object sender, EventArgs e)
        {
            listView_AlarmRecord.Items.Clear();
            int max = 20;
            int retNum = 0;
            List<object> ls = new List<object>();
            for (int i = 0; i < max; i++)
            {
                NET_RECORD_ACCESS_ALARMRECORD_INFO alarm_rec = new NET_RECORD_ACCESS_ALARMRECORD_INFO();
                alarm_rec.dwSize = (uint)Marshal.SizeOf(typeof(NET_RECORD_ACCESS_ALARMRECORD_INFO));
                ls.Add(alarm_rec);
            }
            NETClient.FindNextRecord(m_FindAlarmRecordID, max, ref retNum, ref ls, typeof(NET_RECORD_ACCESS_ALARMRECORD_INFO), 10000);
            BeginInvoke(new Action(() =>
            {
                foreach (var item in ls)
                {
                    NET_RECORD_ACCESS_ALARMRECORD_INFO info = (NET_RECORD_ACCESS_ALARMRECORD_INFO)item;
                    var listitem = new ListViewItem();
                    listitem.Text = info.nRecNo.ToString();
                    DateTime tmpDateTime = new DateTime((int)info.stuTime.dwYear, (int)info.stuTime.dwMonth, (int)info.stuTime.dwDay,
                        (int)info.stuTime.dwHour, (int)info.stuTime.dwMinute, (int)info.stuTime.dwSecond);
                    DateTime retDateTime = Common.Common.GetZoneTimeByUTCTime(tmpDateTime);
                    listitem.SubItems.Add(retDateTime.ToString());
                    listitem.SubItems.Add(info.szUserID);
                    listitem.SubItems.Add(GetAlarmTypeStr(info.emAlarmType));
                    listitem.SubItems.Add(info.nDevAddress.ToString());
                    listitem.SubItems.Add(info.nChannel.ToString());
                    if (listView_AlarmRecord != null)
                    {
                        listView_AlarmRecord.BeginUpdate();
                        listView_AlarmRecord.Items.Add(listitem);
                        listView_AlarmRecord.EndUpdate();
                    }
                }
            }));
        }

        private void btn_StartQueryLog_Click(object sender, EventArgs e)
        {
            m_LogNum = 0;
            if (IntPtr.Zero == m_FindLogID)
            {
                NET_IN_START_QUERYLOG stuIn = new NET_IN_START_QUERYLOG();
                stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_START_QUERYLOG));
                NET_OUT_START_QUERYLOG stuOut = new NET_OUT_START_QUERYLOG();
                stuOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_START_QUERYLOG));

                m_FindLogID = NETClient.StartQueryLog(m_LoginID,ref stuIn,ref stuOut,5000); //CLIENT_StartQueryLog(m_lLoginID, &stuIn, &stuOut, SDK_API_WAIT);
                if (IntPtr.Zero == m_FindLogID)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }

                btn_StartQueryLog.Text = "StopQuery()";
                btn_NextLog.Enabled = true;
                btn_GetLogCount.Enabled = true;
            }
            else
            {
                NETClient.StopQueryLog(m_FindLogID);
                m_FindLogID = IntPtr.Zero;
                btn_StartQueryLog.Text = "StartQuery()";
                btn_NextLog.Enabled = false;
                btn_GetLogCount.Enabled = false;
                txt_LogCount.Text = "";
                listView_Log.Items.Clear();
            }
        }

        private void btn_GetLogCount_Click(object sender, EventArgs e)
        {
            NET_IN_GETCOUNT_LOG_PARAM stuIn = new NET_IN_GETCOUNT_LOG_PARAM();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_GETCOUNT_LOG_PARAM));
            NET_OUT_GETCOUNT_LOG_PARAM stuOut = new NET_OUT_GETCOUNT_LOG_PARAM();
            stuOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_GETCOUNT_LOG_PARAM));
            if (NETClient.QueryDevLogCount(m_LoginID, ref stuIn, ref stuOut, 5000))
            {
                txt_LogCount.Text = stuOut.nLogCount.ToString();
            }
            else
            {
                MessageBox.Show(NETClient.GetLastError());
            }
        }

        private void btn_NextLog_Click(object sender, EventArgs e)
        {
            listView_Log.Items.Clear();
            int max = 20;
            NET_IN_QUERYNEXTLOG stuIn = new NET_IN_QUERYNEXTLOG();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_QUERYNEXTLOG));
            stuIn.nGetCount = max;

            NET_OUT_QUERYNEXTLOG stuOut = new NET_OUT_QUERYNEXTLOG();
            stuOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_QUERYNEXTLOG));
            stuOut.nMaxCount = max;
            stuOut.pstuLogInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_LOG_INFO)) * stuOut.nMaxCount);

            NET_LOG_INFO[] logInfo = new NET_LOG_INFO[stuOut.nMaxCount];
            for (int i = 0; i < stuOut.nMaxCount; i++)
            {
                logInfo[i].dwSize = (uint)Marshal.SizeOf(typeof(NET_LOG_INFO));
                logInfo[i].stuLogMsg.dwSize = (uint)Marshal.SizeOf(typeof(NET_LOG_MESSAGE));
                IntPtr pDst = IntPtr.Add(stuOut.pstuLogInfo, Marshal.SizeOf(typeof(NET_LOG_INFO)) * i);
                Marshal.StructureToPtr(logInfo[i], pDst, true);
            }

            try
            {
                if (NETClient.QueryNextLog(m_FindLogID, ref stuIn, ref stuOut, 5000))
                {
                    if (stuOut.nRetCount > 0)
                    {
                        BeginInvoke(new Action(() =>
                        {
                            for (int i = 0; i < stuOut.nRetCount; i++)
                            {
                                IntPtr pDst = IntPtr.Add(stuOut.pstuLogInfo, Marshal.SizeOf(typeof(NET_LOG_INFO)) * i);
                                NET_LOG_INFO retInfo = (NET_LOG_INFO)Marshal.PtrToStructure(pDst, typeof(NET_LOG_INFO));

                                m_LogNum += 1;
                                var listitem = new ListViewItem();
                                listitem.Text = m_LogNum.ToString();

                                try
                                {
                                    DateTime tmpDateTime = new DateTime((int)retInfo.stuTime.dwYear, (int)retInfo.stuTime.dwMonth, (int)retInfo.stuTime.dwDay,
                                    (int)retInfo.stuTime.dwHour, (int)retInfo.stuTime.dwMinute, (int)retInfo.stuTime.dwSecond);
                                    DateTime retDateTime = Common.Common.GetZoneTimeByUTCTime(tmpDateTime);
                                    listitem.SubItems.Add(retDateTime.ToString());
                                }
                                catch (ArgumentOutOfRangeException exp)
                                {
                                    listitem.SubItems.Add(retInfo.stuTime.ToString());
                                    Console.WriteLine(exp.Message);
                                }
                                //listitem.SubItems.Add(retInfo.stuTime.ToString());
                                listitem.SubItems.Add(retInfo.szUserName);
                                listitem.SubItems.Add(retInfo.szLogType);
                                listitem.SubItems.Add(retInfo.stuLogMsg.szLogMessage);
                                if (listView_Log != null)
                                {
                                    listView_Log.BeginUpdate();
                                    listView_Log.Items.Add(listitem);
                                    listView_Log.EndUpdate();
                                }
                            }
                        }));


                    }
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuOut.pstuLogInfo);
            }
        }

        private string GetAlarmTypeStr(EM_RECORD_ACCESS_ALARM_TYPE em)
        {
            string alarmtype_str = "";

            switch (em)
            {
                case EM_RECORD_ACCESS_ALARM_TYPE.UNKNOWN:
                    alarmtype_str = "UNKNOWN()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.DOOR_NOTCLOSE:
                    alarmtype_str = "DOOR_NOTCLOSE()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.BREAK_IN:
                    alarmtype_str = "BREAK_IN()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.REPEAT_ENTER:
                    alarmtype_str = "REPEAT_ENTER()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.DURESS:
                    alarmtype_str = "DURESS()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.ALARMLOCAL:
                    alarmtype_str = "ALARMLOCAL()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.CHASSIS_INTRUDED:
                    alarmtype_str = "CHASSIS_INTRUDED()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.MALICIOUS:
                    alarmtype_str = "MALICIOUS()";
                    break;
                case EM_RECORD_ACCESS_ALARM_TYPE.BLACKLIST:
                    alarmtype_str = "PROHIBIT(HMD)";
                    break;
                default:
                    alarmtype_str = "UNKNOWN()";
                    break;
            }

            return alarmtype_str;
        }
    }
}
