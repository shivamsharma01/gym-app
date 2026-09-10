namespace AccessDemo2s
{
    partial class UserManageFrom
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.btn_Get = new System.Windows.Forms.Button();
            this.btn_Modify = new System.Windows.Forms.Button();
            this.btn_Add = new System.Windows.Forms.Button();
            this.btn_Delete = new System.Windows.Forms.Button();
            this.dataGridView_user = new System.Windows.Forms.DataGridView();
            this.ID = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.UserID = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.FingerprintID = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.FingerprintData = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.button_GetEigen = new System.Windows.Forms.Button();
            this.button_AddEigen = new System.Windows.Forms.Button();
            this.ip_textBox = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.port_textBox = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.user_textBox = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.pwd_textBox = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.button_LogInEigenDevice = new System.Windows.Forms.Button();
            this.button_GetUserInfo = new System.Windows.Forms.Button();
            this.button_GetCardInfo = new System.Windows.Forms.Button();
            this.button_AddCardInfo = new System.Windows.Forms.Button();
            this.button_AddUserInfo = new System.Windows.Forms.Button();
            this.checkBox1 = new System.Windows.Forms.CheckBox();
            ((System.ComponentModel.ISupportInitialize)(this.dataGridView_user)).BeginInit();
            this.SuspendLayout();
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(568, 62);
            this.btn_Get.Margin = new System.Windows.Forms.Padding(5);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(193, 28);
            this.btn_Get.TabIndex = 18;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // btn_Modify
            // 
            this.btn_Modify.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Modify.Location = new System.Drawing.Point(568, 120);
            this.btn_Modify.Margin = new System.Windows.Forms.Padding(5);
            this.btn_Modify.Name = "btn_Modify";
            this.btn_Modify.Size = new System.Drawing.Size(193, 28);
            this.btn_Modify.TabIndex = 19;
            this.btn_Modify.Text = "Modify()";
            this.btn_Modify.UseVisualStyleBackColor = true;
            this.btn_Modify.Click += new System.EventHandler(this.btn_Modify_Click);
            // 
            // btn_Add
            // 
            this.btn_Add.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Add.Location = new System.Drawing.Point(568, 91);
            this.btn_Add.Margin = new System.Windows.Forms.Padding(5);
            this.btn_Add.Name = "btn_Add";
            this.btn_Add.Size = new System.Drawing.Size(193, 28);
            this.btn_Add.TabIndex = 20;
            this.btn_Add.Text = "Add()";
            this.btn_Add.UseVisualStyleBackColor = true;
            this.btn_Add.Click += new System.EventHandler(this.btn_Add_Click);
            // 
            // btn_Delete
            // 
            this.btn_Delete.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Delete.Location = new System.Drawing.Point(568, 149);
            this.btn_Delete.Margin = new System.Windows.Forms.Padding(5);
            this.btn_Delete.Name = "btn_Delete";
            this.btn_Delete.Size = new System.Drawing.Size(193, 28);
            this.btn_Delete.TabIndex = 21;
            this.btn_Delete.Text = "Delete()";
            this.btn_Delete.UseVisualStyleBackColor = true;
            this.btn_Delete.Click += new System.EventHandler(this.btn_Delete_Click);
            // 
            // dataGridView_user
            // 
            this.dataGridView_user.AllowUserToAddRows = false;
            this.dataGridView_user.AllowUserToResizeRows = false;
            this.dataGridView_user.AutoSizeColumnsMode = System.Windows.Forms.DataGridViewAutoSizeColumnsMode.AllCells;
            this.dataGridView_user.BackgroundColor = System.Drawing.SystemColors.ButtonFace;
            this.dataGridView_user.ColumnHeadersBorderStyle = System.Windows.Forms.DataGridViewHeaderBorderStyle.Single;
            this.dataGridView_user.ColumnHeadersHeightSizeMode = System.Windows.Forms.DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
            this.dataGridView_user.Columns.AddRange(new System.Windows.Forms.DataGridViewColumn[] {
            this.ID,
            this.UserID,
            this.FingerprintID,
            this.FingerprintData});
            this.dataGridView_user.EditMode = System.Windows.Forms.DataGridViewEditMode.EditProgrammatically;
            this.dataGridView_user.Location = new System.Drawing.Point(12, 12);
            this.dataGridView_user.MultiSelect = false;
            this.dataGridView_user.Name = "dataGridView_user";
            this.dataGridView_user.ReadOnly = true;
            this.dataGridView_user.RowHeadersVisible = false;
            this.dataGridView_user.RowTemplate.Height = 23;
            this.dataGridView_user.SelectionMode = System.Windows.Forms.DataGridViewSelectionMode.FullRowSelect;
            this.dataGridView_user.Size = new System.Drawing.Size(534, 560);
            this.dataGridView_user.TabIndex = 22;
            // 
            // ID
            // 
            this.ID.HeaderText = "No.()";
            this.ID.Name = "ID";
            this.ID.ReadOnly = true;
            this.ID.Width = 95;
            // 
            // UserID
            // 
            this.UserID.HeaderText = "User ID()";
            this.UserID.Name = "UserID";
            this.UserID.ReadOnly = true;
            this.UserID.Width = 151;
            // 
            // FingerprintID
            // 
            this.FingerprintID.HeaderText = "User Name()";
            this.FingerprintID.Name = "FingerprintID";
            this.FingerprintID.ReadOnly = true;
            this.FingerprintID.Width = 151;
            // 
            // FingerprintData
            // 
            this.FingerprintData.HeaderText = "Type()";
            this.FingerprintData.Name = "FingerprintData";
            this.FingerprintData.ReadOnly = true;
            this.FingerprintData.Width = 102;
            // 
            // button_GetEigen
            // 
            this.button_GetEigen.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_GetEigen.Location = new System.Drawing.Point(568, 254);
            this.button_GetEigen.Margin = new System.Windows.Forms.Padding(5);
            this.button_GetEigen.Name = "button_GetEigen";
            this.button_GetEigen.Size = new System.Drawing.Size(195, 28);
            this.button_GetEigen.TabIndex = 23;
            this.button_GetEigen.Text = "GetEigen()";
            this.button_GetEigen.UseVisualStyleBackColor = true;
            this.button_GetEigen.Click += new System.EventHandler(this.button_GetEigen_Click);
            // 
            // button_AddEigen
            // 
            this.button_AddEigen.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_AddEigen.Location = new System.Drawing.Point(568, 542);
            this.button_AddEigen.Margin = new System.Windows.Forms.Padding(5);
            this.button_AddEigen.Name = "button_AddEigen";
            this.button_AddEigen.Size = new System.Drawing.Size(195, 28);
            this.button_AddEigen.TabIndex = 24;
            this.button_AddEigen.Text = "AddEigen()";
            this.button_AddEigen.UseVisualStyleBackColor = true;
            this.button_AddEigen.Click += new System.EventHandler(this.button_AddEigen_Click);
            // 
            // ip_textBox
            // 
            this.ip_textBox.Location = new System.Drawing.Point(638, 300);
            this.ip_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.ip_textBox.Name = "ip_textBox";
            this.ip_textBox.Size = new System.Drawing.Size(120, 23);
            this.ip_textBox.TabIndex = 26;
            this.ip_textBox.Text = "192.168.1.27";
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(602, 303);
            this.label1.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(28, 14);
            this.label1.TabIndex = 25;
            this.label1.Text = "IP:";
            // 
            // port_textBox
            // 
            this.port_textBox.Location = new System.Drawing.Point(638, 331);
            this.port_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.port_textBox.Name = "port_textBox";
            this.port_textBox.Size = new System.Drawing.Size(80, 23);
            this.port_textBox.TabIndex = 28;
            this.port_textBox.Text = "37777";
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(588, 334);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(42, 14);
            this.label2.TabIndex = 27;
            this.label2.Text = "Port:";
            // 
            // user_textBox
            // 
            this.user_textBox.Location = new System.Drawing.Point(638, 362);
            this.user_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.user_textBox.Name = "user_textBox";
            this.user_textBox.Size = new System.Drawing.Size(120, 23);
            this.user_textBox.TabIndex = 30;
            this.user_textBox.Text = "admin";
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(588, 365);
            this.label3.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(42, 14);
            this.label3.TabIndex = 29;
            this.label3.Text = "Name:";
            // 
            // pwd_textBox
            // 
            this.pwd_textBox.Location = new System.Drawing.Point(638, 393);
            this.pwd_textBox.Margin = new System.Windows.Forms.Padding(4);
            this.pwd_textBox.Name = "pwd_textBox";
            this.pwd_textBox.Size = new System.Drawing.Size(120, 23);
            this.pwd_textBox.TabIndex = 32;
            this.pwd_textBox.Text = "tipl9910";
            this.pwd_textBox.UseSystemPasswordChar = true;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(560, 396);
            this.label4.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(70, 14);
            this.label4.TabIndex = 31;
            this.label4.Text = "Password:";
            // 
            // button_LogInEigenDevice
            // 
            this.button_LogInEigenDevice.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_LogInEigenDevice.Location = new System.Drawing.Point(568, 433);
            this.button_LogInEigenDevice.Margin = new System.Windows.Forms.Padding(5);
            this.button_LogInEigenDevice.Name = "button_LogInEigenDevice";
            this.button_LogInEigenDevice.Size = new System.Drawing.Size(195, 28);
            this.button_LogInEigenDevice.TabIndex = 33;
            this.button_LogInEigenDevice.Text = "LogInEigenDevice";
            this.button_LogInEigenDevice.UseVisualStyleBackColor = true;
            this.button_LogInEigenDevice.Click += new System.EventHandler(this.button_LogInEigenDevice_Click);
            // 
            // button_GetUserInfo
            // 
            this.button_GetUserInfo.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_GetUserInfo.Location = new System.Drawing.Point(568, 178);
            this.button_GetUserInfo.Margin = new System.Windows.Forms.Padding(5);
            this.button_GetUserInfo.Name = "button_GetUserInfo";
            this.button_GetUserInfo.Size = new System.Drawing.Size(195, 28);
            this.button_GetUserInfo.TabIndex = 34;
            this.button_GetUserInfo.Text = "GetUserInfo()";
            this.button_GetUserInfo.UseVisualStyleBackColor = true;
            this.button_GetUserInfo.Click += new System.EventHandler(this.button_GetUserInfo_Click);
            // 
            // button_GetCardInfo
            // 
            this.button_GetCardInfo.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_GetCardInfo.Location = new System.Drawing.Point(568, 216);
            this.button_GetCardInfo.Margin = new System.Windows.Forms.Padding(5);
            this.button_GetCardInfo.Name = "button_GetCardInfo";
            this.button_GetCardInfo.Size = new System.Drawing.Size(195, 28);
            this.button_GetCardInfo.TabIndex = 35;
            this.button_GetCardInfo.Text = "GetCardInfo()";
            this.button_GetCardInfo.UseVisualStyleBackColor = true;
            this.button_GetCardInfo.Click += new System.EventHandler(this.button_GetCardInfo_Click);
            // 
            // button_AddCardInfo
            // 
            this.button_AddCardInfo.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_AddCardInfo.Location = new System.Drawing.Point(568, 506);
            this.button_AddCardInfo.Margin = new System.Windows.Forms.Padding(5);
            this.button_AddCardInfo.Name = "button_AddCardInfo";
            this.button_AddCardInfo.Size = new System.Drawing.Size(195, 28);
            this.button_AddCardInfo.TabIndex = 36;
            this.button_AddCardInfo.Text = "AddCardInfo()";
            this.button_AddCardInfo.UseVisualStyleBackColor = true;
            this.button_AddCardInfo.Click += new System.EventHandler(this.button_AddCardInfo_Click);
            // 
            // button_AddUserInfo
            // 
            this.button_AddUserInfo.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button_AddUserInfo.Location = new System.Drawing.Point(568, 471);
            this.button_AddUserInfo.Margin = new System.Windows.Forms.Padding(5);
            this.button_AddUserInfo.Name = "button_AddUserInfo";
            this.button_AddUserInfo.Size = new System.Drawing.Size(195, 28);
            this.button_AddUserInfo.TabIndex = 37;
            this.button_AddUserInfo.Text = "AddUserInfo()";
            this.button_AddUserInfo.UseVisualStyleBackColor = true;
            this.button_AddUserInfo.Click += new System.EventHandler(this.button_AddUserInfo_Click);
            // 
            // checkBox1
            // 
            this.checkBox1.Appearance = System.Windows.Forms.Appearance.Button;
            this.checkBox1.AutoSize = true;
            this.checkBox1.BackColor = System.Drawing.Color.Red;
            this.checkBox1.Font = new System.Drawing.Font("Microsoft YaHei", 12F, System.Drawing.FontStyle.Bold, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.checkBox1.ForeColor = System.Drawing.Color.Transparent;
            this.checkBox1.Location = new System.Drawing.Point(555, 22);
            this.checkBox1.Name = "checkBox1";
            this.checkBox1.Size = new System.Drawing.Size(217, 32);
            this.checkBox1.TabIndex = 40;
            this.checkBox1.Text = "  Disable Enroll Function";
            this.checkBox1.UseVisualStyleBackColor = false;
            this.checkBox1.CheckedChanged += new System.EventHandler(this.CheckBox1_CheckedChanged);
            // 
            // UserManageFrom
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(784, 584);
            this.Controls.Add(this.checkBox1);
            this.Controls.Add(this.button_AddUserInfo);
            this.Controls.Add(this.button_AddCardInfo);
            this.Controls.Add(this.button_GetCardInfo);
            this.Controls.Add(this.button_GetUserInfo);
            this.Controls.Add(this.button_LogInEigenDevice);
            this.Controls.Add(this.pwd_textBox);
            this.Controls.Add(this.label4);
            this.Controls.Add(this.user_textBox);
            this.Controls.Add(this.label3);
            this.Controls.Add(this.port_textBox);
            this.Controls.Add(this.label2);
            this.Controls.Add(this.ip_textBox);
            this.Controls.Add(this.label1);
            this.Controls.Add(this.button_AddEigen);
            this.Controls.Add(this.button_GetEigen);
            this.Controls.Add(this.dataGridView_user);
            this.Controls.Add(this.btn_Delete);
            this.Controls.Add(this.btn_Add);
            this.Controls.Add(this.btn_Modify);
            this.Controls.Add(this.btn_Get);
            this.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "UserManageFrom";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "UserManage()";
            this.Load += new System.EventHandler(this.UserManageFrom_Load);
            ((System.ComponentModel.ISupportInitialize)(this.dataGridView_user)).EndInit();
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.Button btn_Modify;
        private System.Windows.Forms.Button btn_Add;
        private System.Windows.Forms.Button btn_Delete;
        private System.Windows.Forms.DataGridView dataGridView_user;
        private System.Windows.Forms.Button button_GetEigen;
        private System.Windows.Forms.Button button_AddEigen;
        private System.Windows.Forms.TextBox ip_textBox;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.TextBox port_textBox;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox user_textBox;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.TextBox pwd_textBox;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.Button button_LogInEigenDevice;
        private System.Windows.Forms.Button button_GetUserInfo;
        private System.Windows.Forms.Button button_GetCardInfo;
        private System.Windows.Forms.Button button_AddCardInfo;
        private System.Windows.Forms.DataGridViewTextBoxColumn ID;
        private System.Windows.Forms.DataGridViewTextBoxColumn UserID;
        private System.Windows.Forms.DataGridViewTextBoxColumn FingerprintID;
        private System.Windows.Forms.DataGridViewTextBoxColumn FingerprintData;
        private System.Windows.Forms.Button button_AddUserInfo;
        private System.Windows.Forms.CheckBox checkBox1;
    }
}