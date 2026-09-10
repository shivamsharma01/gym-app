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
    public partial class AccessPasswordForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private int m_Channel = 0;
        private List<int> m_selectDoorsList = new List<int>();
        private NET_RECORDSET_ACCESS_CTL_PWD update_pwd = new NET_RECORDSET_ACCESS_CTL_PWD();

        public AccessPasswordForm()
        {
            InitializeComponent();
        }

        public AccessPasswordForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_Channel = channel;
            cmb_OperateType.SelectedIndex = 0;
        }

        private void cmb_OperateType_SelectedIndexChanged(object sender, EventArgs e)
        {
            ClearData();

            btn_Get.Visible = false;
            btn_Confirm.Enabled = true;
            txt_Password.ReadOnly = false;
            btn_Door.Enabled = true;

            if (cmb_OperateType.SelectedIndex == 0)
            {
                txt_RecNum.ReadOnly = true;
            }
            else if (cmb_OperateType.SelectedIndex == 1)
            {
                txt_RecNum.ReadOnly = false;
            }
            else if (cmb_OperateType.SelectedIndex == 2)
            {
                txt_RecNum.ReadOnly = false;
                btn_Get.Visible = true;
                btn_Get.Enabled = true;
                btn_Confirm.Enabled = false;
            }
            else if (cmb_OperateType.SelectedIndex == 3)
            {
                txt_RecNum.ReadOnly = false;
                txt_Password.ReadOnly = true;
                btn_Door.Enabled = false;
            }
            else if(cmb_OperateType.SelectedIndex == 4)
            {
                txt_RecNum.ReadOnly = true;
                txt_Password.ReadOnly = true;
                btn_Door.Enabled = false;
            }
        }

        private void btn_Door_Click(object sender, EventArgs e)
        {
            List<int> doors = new List<int>();
            for (int i = 0; i < update_pwd.nDoorNum; i++)
            {
                doors.Add(update_pwd.sznDoors[i]);
            }
            DoorSelectForm doorForm = new DoorSelectForm(m_Channel, doors);
            doorForm.ShowDialog();
            if (doorForm.DialogResult == DialogResult.OK)
            {
                m_selectDoorsList.Clear();
                m_selectDoorsList = doorForm.SelectDoorsList;
            }
            doorForm.Dispose();
        }

        private void btn_Confirm_Click(object sender, EventArgs e)
        {
            switch (cmb_OperateType.SelectedIndex)
            {
                case 0:
                    InsertRecordSet();
                    break;
                case 1:
                    GetRecordSet();
                    break;
                case 2:
                    UpdateRecordSet();
                    break;
                case 3:
                    RemoveRecordSet();
                    break;
                case 4:
                    ClearRecordSet();
                    break;
                default:
                    break;
            }
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            if (GetRecordSet())
            {
                txt_RecNum.ReadOnly = true;
                btn_Get.Enabled = false;
                btn_Confirm.Enabled = true;
            }
        }

        //constructing arrays in the struct. this method can make sure the array's size is right
        public void InitStruct(ref object stu)
        {
            IntPtr p_stu = IntPtr.Zero;
            Type type = stu.GetType();
            try
            {
                p_stu = Marshal.AllocHGlobal(Marshal.SizeOf(type));
                Marshal.StructureToPtr(stu, p_stu, true);
                stu = Marshal.PtrToStructure(p_stu, type);
            }
            finally
            {
                Marshal.FreeHGlobal(p_stu);
            }
        }

        private void InsertRecordSet()
        {
            IntPtr pParam = IntPtr.Zero;
            IntPtr pBuf = IntPtr.Zero;
            NET_CTRL_RECORDSET_INSERT_PARAM stuInsertParam = new NET_CTRL_RECORDSET_INSERT_PARAM();
            NET_CTRL_RECORDSET_INSERT_PARAM stuOutParam = new NET_CTRL_RECORDSET_INSERT_PARAM();

            NET_RECORDSET_ACCESS_CTL_PWD stuPwd = new NET_RECORDSET_ACCESS_CTL_PWD();
            object obj = stuPwd;
            InitStruct(ref obj);
            stuPwd = (NET_RECORDSET_ACCESS_CTL_PWD)obj;
            stuPwd.dwSize = (uint)Marshal.SizeOf(stuPwd);
            byte[] pwdArray = Encoding.Default.GetBytes(txt_Password.Text);
            if (pwdArray.Length > 64)
            {
                MessageBox.Show("Password length cannot be greater than 64(64)。");
                return;
            }
            Encoding.Default.GetBytes(txt_Password.Text, 0, txt_Password.Text.Length, stuPwd.szDoorOpenPwd, 0);
            if (m_selectDoorsList.Count > 0)
            {
                for (int i = 0; i < m_selectDoorsList.Count; i++)
                {
                    stuPwd.sznDoors[i] = m_selectDoorsList[i];
                }
            }
            else
            {
                stuPwd.sznDoors = new int[32];
            }
            stuPwd.nDoorNum = m_selectDoorsList.Count;

            try
            {
                pParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_CTRL_RECORDSET_INSERT_PARAM)));
                pBuf = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_RECORDSET_ACCESS_CTL_PWD)));

                Marshal.StructureToPtr(stuPwd, pBuf, true);

                //package stuInsertParam
                stuInsertParam.stuCtrlRecordSetInfo.pBuf = pBuf;
                stuInsertParam.stuCtrlRecordSetInfo.nBufLen = Marshal.SizeOf(stuPwd);
                stuInsertParam.dwSize = (uint)Marshal.SizeOf(stuInsertParam);
                stuInsertParam.stuCtrlRecordSetInfo.dwSize = (uint)Marshal.SizeOf(stuInsertParam.stuCtrlRecordSetInfo);
                stuInsertParam.stuCtrlRecordSetInfo.emType = EM_NET_RECORD_TYPE.ACCESSCTLPWD;
                stuInsertParam.stuCtrlRecordSetResult.dwSize = (uint)Marshal.SizeOf(typeof(NET_CTRL_RECORDSET_INSERT_OUT));
                Marshal.StructureToPtr(stuInsertParam, pParam, true);


                bool bRet = NETClient.ControlDevice(m_LoginID, EM_CtrlType.RECORDSET_INSERT, pParam, 3000);

                stuOutParam = (NET_CTRL_RECORDSET_INSERT_PARAM)Marshal.PtrToStructure(pParam, typeof(NET_CTRL_RECORDSET_INSERT_PARAM));
                if (bRet)
                {
                    MessageBox.Show("Insert succeed()。RecNO():" + stuOutParam.stuCtrlRecordSetResult.nRecNo);
                    ClearData();
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
            //free resource
            finally
            {
                Marshal.FreeHGlobal(pParam);
                Marshal.FreeHGlobal(pBuf);
            }
        }

        private bool GetRecordSet()
        {
            bool get_result = false;
            int temp;
            if (!int.TryParse(txt_RecNum.Text, out temp))
            {
                MessageBox.Show("Num is illegal()！");
                return get_result;
            }
            IntPtr pBuf = IntPtr.Zero;

            NET_RECORDSET_ACCESS_CTL_PWD result = new NET_RECORDSET_ACCESS_CTL_PWD();
            NET_CTRL_RECORDSET_PARAM stuParam = new NET_CTRL_RECORDSET_PARAM();

            try
            {
                pBuf = Marshal.AllocHGlobal(Marshal.SizeOf(result));

                //package for pwd
                result.nRecNo = int.Parse(txt_RecNum.Text);
                result.dwSize = (uint)Marshal.SizeOf(result);
                Marshal.StructureToPtr(result, pBuf, true);

                //package stuParam
                stuParam.pBuf = pBuf;
                stuParam.nBufLen = Marshal.SizeOf(result);
                stuParam.emType = EM_NET_RECORD_TYPE.ACCESSCTLPWD;
                stuParam.dwSize = (uint)Marshal.SizeOf(stuParam);
                object obj = stuParam;

                bool bRet = NETClient.QueryDevState(m_LoginID, (int)EM_DEVICE_STATE.DEV_RECORDSET, ref obj, typeof(NET_CTRL_RECORDSET_PARAM), 3000);
                if (bRet)
                {
                    update_pwd = (NET_RECORDSET_ACCESS_CTL_PWD)Marshal.PtrToStructure(pBuf, typeof(NET_RECORDSET_ACCESS_CTL_PWD));
                    txt_RecNum.Text = update_pwd.nRecNo.ToString();
                    txt_Password.Text = Encoding.UTF8.GetString(update_pwd.szDoorOpenPwd);

                    m_selectDoorsList.Clear();
                    for (int i = 0; i < update_pwd.nDoorNum; i++)
                    {
                        m_selectDoorsList.Add(update_pwd.sznDoors[i]);
                    }

                    MessageBox.Show("Get succeed()。");
                    get_result = true;
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
                Marshal.FreeHGlobal(pBuf);
            }
            return get_result;
        }

        private void UpdateRecordSet()
        {
            byte[] pwdArray = Encoding.Default.GetBytes(txt_Password.Text);
            if (pwdArray.Length > 64)
            {
                MessageBox.Show("Password length cannot be greater than 64(64)。");
                return;
            }

            update_pwd.szDoorOpenPwd = new byte[64];
            Encoding.Default.GetBytes(txt_Password.Text, 0, txt_Password.Text.Length, update_pwd.szDoorOpenPwd, 0);
            if (m_selectDoorsList.Count > 0)
            {
                for (int i = 0; i < m_selectDoorsList.Count; i++)
                {
                    update_pwd.sznDoors[i] = m_selectDoorsList[i];
                }
            }
            else
            {
                update_pwd.sznDoors = new int[32];
            }
            update_pwd.nDoorNum = m_selectDoorsList.Count;


            bool bRet = false;
            IntPtr pParam = IntPtr.Zero;
            IntPtr pBuf = IntPtr.Zero;
            NET_CTRL_RECORDSET_PARAM stuParam = new NET_CTRL_RECORDSET_PARAM();
            try
            {
                pParam = Marshal.AllocHGlobal(Marshal.SizeOf(stuParam));
                pBuf = Marshal.AllocHGlobal(Marshal.SizeOf(update_pwd));


                Marshal.StructureToPtr(update_pwd, pBuf, true);
                stuParam.pBuf = pBuf;
                stuParam.nBufLen = Marshal.SizeOf(update_pwd);
                stuParam.emType = EM_NET_RECORD_TYPE.ACCESSCTLPWD;
                stuParam.dwSize = (uint)Marshal.SizeOf(stuParam);
                Marshal.StructureToPtr(stuParam, pParam, true);

                bRet = NETClient.ControlDevice(m_LoginID, EM_CtrlType.RECORDSET_UPDATE, pParam, 3000);
                if (bRet)
                {
                    MessageBox.Show("Update succeed()。");
                    ClearData();
                    txt_RecNum.ReadOnly = false;
                    btn_Get.Enabled = true;
                    btn_Confirm.Enabled = false;
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
                Marshal.FreeHGlobal(pParam);
                Marshal.FreeHGlobal(pBuf);
            }
        }

        private void RemoveRecordSet()
        {
            int temp;
            if (!int.TryParse(txt_RecNum.Text, out temp))
            {
                MessageBox.Show("Num is illegal()！");
                return;
            }

            bool result = false;
            IntPtr pParam = IntPtr.Zero;
            IntPtr pBuf = IntPtr.Zero;
            NET_CTRL_RECORDSET_PARAM stuParam = new NET_CTRL_RECORDSET_PARAM();
            stuParam.emType = EM_NET_RECORD_TYPE.ACCESSCTLPWD;
            stuParam.dwSize = (uint)Marshal.SizeOf(stuParam);
            stuParam.pBuf = IntPtr.Zero;
            stuParam.nBufLen = 0;
            try
            {
                pParam = Marshal.AllocHGlobal(Marshal.SizeOf(stuParam));
                pBuf = Marshal.AllocHGlobal(Marshal.SizeOf(int.Parse(txt_RecNum.Text)));
                Marshal.StructureToPtr(int.Parse(txt_RecNum.Text), pBuf, true);
                stuParam.pBuf = pBuf;
                stuParam.nBufLen = Marshal.SizeOf(int.Parse(txt_RecNum.Text));
                Marshal.StructureToPtr(stuParam, pParam, true);

                result = NETClient.ControlDevice(m_LoginID, EM_CtrlType.RECORDSET_REMOVE, pParam, 3000);
                if (result)
                {
                    MessageBox.Show("Remove succeed()。");
                    ClearData();
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
                Marshal.FreeHGlobal(pBuf);
                Marshal.FreeHGlobal(pParam);
            }
        }

        private void ClearRecordSet()
        {
            IntPtr pParam = IntPtr.Zero;
            NET_CTRL_RECORDSET_PARAM stuParam = new NET_CTRL_RECORDSET_PARAM();
            stuParam.emType = EM_NET_RECORD_TYPE.ACCESSCTLPWD;
            stuParam.dwSize = (uint)Marshal.SizeOf(stuParam);

            pParam = Marshal.AllocHGlobal(Marshal.SizeOf(stuParam));
            Marshal.StructureToPtr(stuParam, pParam, true);

            try
            {
                bool result = NETClient.ControlDevice(m_LoginID, EM_CtrlType.RECORDSET_CLEAR, pParam, 3000);
                if (result)
                {
                    MessageBox.Show("Clear succeed()。");
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
                Marshal.FreeHGlobal(pParam);
            }
        }

        private void ClearData()
        {
            txt_RecNum.Text = "";
            txt_Password.Text = "";
            m_selectDoorsList.Clear();
            update_pwd = new NET_RECORDSET_ACCESS_CTL_PWD();
        }
    }
}
